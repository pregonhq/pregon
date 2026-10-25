package io.github.pregonhq.pregon.server.transcription;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.core.ingest.SegmentQueue.Job;
import io.github.pregonhq.pregon.spi.speech.AudioSegment;
import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import io.github.pregonhq.pregon.spi.speech.SpeechToTextException;
import io.github.pregonhq.pregon.spi.speech.TranscriptionRequest;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Drains the segment queue through the speech-to-text port (ADR-0025 §9, ADR-0027): claims a
 * batch, sends it to the sidecar, stores each transcript if retention asks for it, and reports
 * every job back as completed or failed under the attempt it was claimed with.
 *
 * <p>One loop per server instance: transcription, not this loop, is the bottleneck, and the
 * sidecar batches. The loop polls; waking on {@code LISTEN/NOTIFY} is the second step of ADR-0025.
 *
 * <p>When the sidecar cannot take a batch, the batch is returned to the queue right away and the
 * worker stops claiming until the sidecar's health says it is back: every claim costs the segment
 * an attempt, so claiming against a sidecar that is down would turn an outage into
 * {@code FAILED} segments. Logs carry ids, counts and milliseconds, never text (ADR-0006).
 */
public final class TranscriptionWorker implements SmartLifecycle {

    /** Where a transcript goes once it exists; {@link #NONE} when transcripts are not retained. */
    public interface TranscriptSink {
        TranscriptSink NONE = (job, result) -> { };

        void save(Job job, TranscriptionResult.Transcribed result);
    }

    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);
    /** How long a reason may be when it is stored next to a segment. */
    private static final int REASON_LIMIT = 500;

    private final SegmentQueue queue;
    private final SpeechToText stt;
    private final TranscriptSink sink;
    private final BooleanSupplier sidecarUp;
    private final TranscriptionProperties properties;
    private final Clock clock;

    private volatile boolean running;
    private Thread thread;
    private boolean recovering;
    private Instant pausedUntil = Instant.MIN;

    public TranscriptionWorker(SegmentQueue queue, SpeechToText stt, TranscriptSink sink, BooleanSupplier sidecarUp,
            TranscriptionProperties properties, Clock clock) {
        this.queue = queue;
        this.stt = stt;
        this.sink = sink;
        this.sidecarUp = sidecarUp;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One pass: claims and processes at most one batch. Returns how many jobs were reported back,
     * zero when the queue was empty or the worker is waiting for the sidecar; the loop sleeps
     * {@code poll} on zero and goes straight back on anything else.
     */
    int runOnce() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return 0;
        }
        if (recovering) {
            if (!sidecarUp.getAsBoolean()) {
                pausedUntil = now.plus(properties.poll());
                return 0;
            }
            recovering = false;
            log.info("sidecar is back, resuming");
        }
        List<Job> jobs = queue.claim(stt.maxBatchSize(), properties.lease());
        if (jobs.isEmpty()) {
            return 0;
        }
        List<Job> withAudio = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            if (job.audio().length == 0) {
                queue.fail(job.tenantId(), job.segmentId(), job.attempt(), "audio missing from the queue");
                log.warn("segment {} of tenant {} attempt {} has no audio in the queue", job.segmentId(), job.tenantId(), job.attempt());
            } else {
                withAudio.add(job);
            }
        }
        // Ids come from agents and two tenants may share one; a request needs distinct ids, so such a
        // batch goes in rounds, each with one job per id, instead of failing anybody.
        for (List<Job> round : rounds(withAudio)) {
            if (!transcribe(round)) {
                break;
            }
        }
        return jobs.size();
    }

    static List<List<Job>> rounds(List<Job> jobs) {
        List<List<Job>> rounds = new ArrayList<>();
        for (Job job : jobs) {
            List<Job> round = rounds.stream()
                    .filter(r -> r.stream().noneMatch(other -> other.segmentId().equals(job.segmentId())))
                    .findFirst().orElse(null);
            if (round == null) {
                round = new ArrayList<>();
                rounds.add(round);
            }
            round.add(job);
        }
        return rounds;
    }

    /** One request to the sidecar; false when the sidecar could not take it and the pass must stop. */
    private boolean transcribe(List<Job> round) {
        List<AudioSegment> audio = round.stream().map(job -> new AudioSegment(job.segmentId(), job.audio())).toList();
        long started = System.nanoTime();
        List<TranscriptionResult> results;
        try {
            results = stt.transcribe(new TranscriptionRequest(properties.language(), audio));
        } catch (SpeechToTextException e) {
            returnBatch(round, e);
            return false;
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        Map<UUID, Job> byId = new HashMap<>();
        round.forEach(job -> byId.put(job.segmentId(), job));
        int failed = 0;
        for (TranscriptionResult result : results) {
            Job job = byId.get(result.segmentId());
            switch (result) {
                case TranscriptionResult.Transcribed transcribed -> {
                    sink.save(job, transcribed);
                    SegmentQueue.CompleteOutcome outcome = queue.complete(job.tenantId(), job.segmentId(), job.attempt());
                    if (outcome == SegmentQueue.CompleteOutcome.STALE) {
                        log.warn("segment {} attempt {} was transcribed after its lease; the result of the current owner stands",
                                job.segmentId(), job.attempt());
                    }
                }
                case TranscriptionResult.Failed failure -> {
                    failed++;
                    SegmentQueue.FailOutcome outcome = queue.fail(job.tenantId(), job.segmentId(), job.attempt(), truncate(failure.reason()));
                    log.warn("segment {} attempt {} could not be transcribed ({}): {}", job.segmentId(), job.attempt(), outcome,
                            truncate(failure.reason()));
                }
            }
        }
        log.info("transcribed a batch of {} segments in {} ms, {} failed", results.size(), elapsedMillis, failed);
        return true;
    }

    /**
     * The whole batch could not be processed. Every job goes back to the queue under its attempt;
     * if the sidecar may take it later, the worker waits for the sidecar's health before claiming
     * anything else. A permanent refusal points at a bug on this side: the jobs go back too, so
     * they are retried up to the maximum and the failure is recorded on each, and the worker pauses
     * one poll so that a broken batch does not spin.
     */
    private void returnBatch(List<Job> jobs, SpeechToTextException e) {
        String reason = truncate(e.getMessage());
        for (Job job : jobs) {
            queue.fail(job.tenantId(), job.segmentId(), job.attempt(), reason);
        }
        Instant now = clock.instant();
        if (e.isRetryable()) {
            recovering = true;
            pausedUntil = now.plus(e.retryAfter().orElse(properties.poll()));
            log.warn("sidecar could not take a batch of {} segments, retrying after {}: {}", jobs.size(),
                    Duration.between(now, pausedUntil), reason);
        } else {
            pausedUntil = now.plus(properties.poll());
            log.error("sidecar refused a batch of {} segments: {}", jobs.size(), reason);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= REASON_LIMIT ? reason : reason.substring(0, REASON_LIMIT);
    }

    private void loop() {
        log.info("transcription worker started: batch {}, lease {}, poll {}, language {}", stt.maxBatchSize(),
                properties.lease(), properties.poll(), properties.language());
        while (running) {
            int processed;
            try {
                processed = runOnce();
            } catch (RuntimeException e) {
                // The database or the store failed: the leases expire on their own, nothing is lost.
                log.error("transcription pass failed, trying again after {}", properties.poll(), e);
                processed = 0;
            }
            if (processed == 0 && running) {
                try {
                    Thread.sleep(properties.poll());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("transcription-worker").daemon(true).start(this::loop);
    }

    /** Lets the pass in flight finish reporting; a job it did not report keeps its lease and expires. */
    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

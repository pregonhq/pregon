package io.github.pregonhq.pregon.server.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.server.ingest.MutableClock;
import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import io.github.pregonhq.pregon.spi.speech.SpeechToTextException;
import io.github.pregonhq.pregon.spi.speech.TranscriptionRequest;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.junit.jupiter.api.Test;

/**
 * One pass of the worker against an in-memory queue and a scripted speech-to-text port: what is
 * reported back, under which attempt, and when the worker refuses to claim (ADR-0025 §9, ADR-0027).
 */
class TranscriptionWorkerTest {

    static final Duration LEASE = Duration.ofMinutes(6);
    static final Duration POLL = Duration.ofSeconds(2);
    static final TranscriptionProperties PROPERTIES = new TranscriptionProperties("es", LEASE, POLL, Optional.of(Duration.ofDays(7)));

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T10:00:00Z"));
    final FakeQueue queue = new FakeQueue();
    final ScriptedStt stt = new ScriptedStt();
    final List<String> stored = new ArrayList<>();
    final AtomicBoolean sidecarUp = new AtomicBoolean(true);
    final TranscriptionWorker worker = new TranscriptionWorker(queue, stt, (job, result) -> stored.add(job.segmentId() + "=" + result.text()),
            sidecarUp::get, PROPERTIES, clock);

    @Test
    void transcribesABatchStoresEachTranscriptAndCompletesEveryJobUnderItsAttempt() {
        UUID a = queue.add("t1", "a-bytes");
        UUID b = queue.add("t1", "b-bytes");
        stt.script = request -> request.segments().stream()
                .<TranscriptionResult>map(s -> transcribed(s.id(), "hola " + s.id()))
                .toList();

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(2);
        assertThat(stt.requests).singleElement().satisfies(request -> {
            assertThat(request.language()).isEqualTo("es");
            assertThat(request.segments()).extracting("id").containsExactly(a, b);
        });
        assertThat(stored).containsExactly(a + "=hola " + a, b + "=hola " + b);
        assertThat(queue.completed).containsExactly("t1:" + a + "@1", "t1:" + b + "@1");
        assertThat(queue.failed).isEmpty();
        assertThat(queue.claimedWithLease).containsExactly(LEASE);
    }

    @Test
    void aFailedResultFailsItsJobOnlyAndTheOthersComplete() {
        UUID good = queue.add("t1", "good");
        UUID bad = queue.add("t1", "bad");
        stt.script = request -> List.of(transcribed(good, "hola"), new TranscriptionResult.Failed(bad, "audio could not be decoded"));

        worker.runOnce();

        assertThat(queue.completed).containsExactly("t1:" + good + "@1");
        assertThat(queue.failed).containsExactly("t1:" + bad + "@1: audio could not be decoded");
        assertThat(stored).containsExactly(good + "=hola");
    }

    @Test
    void aJobWithoutAudioIsFailedWithoutReachingTheSidecar() {
        UUID empty = queue.add("t1", "");
        UUID full = queue.add("t1", "bytes");
        stt.script = request -> request.segments().stream().<TranscriptionResult>map(s -> transcribed(s.id(), "")).toList();

        worker.runOnce();

        assertThat(stt.requests).singleElement().satisfies(request -> assertThat(request.segments()).extracting("id").containsExactly(full));
        assertThat(queue.failed).singleElement().asString().startsWith("t1:" + empty + "@1: audio missing");
        assertThat(queue.completed).containsExactly("t1:" + full + "@1");
    }

    @Test
    void anEmptyQueueIsNotACall() {
        assertThat(worker.runOnce()).isZero();
        assertThat(stt.requests).isEmpty();
    }

    @Test
    void whenTheSidecarIsBusyTheBatchGoesBackAndNothingIsClaimedUntilItIsUpAgain() {
        UUID a = queue.add("t1", "a");
        stt.script = request -> {
            throw SpeechToTextException.retryable("sidecar busy", Duration.ofSeconds(30), null);
        };

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.failed).containsExactly("t1:" + a + "@1: sidecar busy");
        assertThat(queue.pending()).containsExactly(a);

        // Within Retry-After: no claim, whatever the sidecar says.
        clock.advance(Duration.ofSeconds(29));
        assertThat(worker.runOnce()).isZero();
        assertThat(queue.claims).isEqualTo(1);

        // After it, still down according to its health: one poll more, no claim.
        clock.advance(Duration.ofSeconds(2));
        sidecarUp.set(false);
        assertThat(worker.runOnce()).isZero();
        assertThat(queue.claims).isEqualTo(1);
        clock.advance(POLL);
        assertThat(worker.runOnce()).isZero();
        assertThat(queue.claims).isEqualTo(1);

        // Up again: the job is claimed with its next attempt and transcribed.
        sidecarUp.set(true);
        stt.script = request -> List.of(transcribed(a, "hola"));
        clock.advance(POLL);
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.completed).containsExactly("t1:" + a + "@2");
    }

    @Test
    void aPermanentRefusalFailsTheBatchAndPausesOnePollSoABrokenBatchDoesNotSpin() {
        UUID a = queue.add("t1", "a");
        stt.script = request -> {
            throw SpeechToTextException.permanent("the sidecar rejected the request");
        };

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.failed).containsExactly("t1:" + a + "@1: the sidecar rejected the request");

        assertThat(worker.runOnce()).isZero();
        clock.advance(POLL);
        stt.script = request -> List.of(transcribed(a, "hola"));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.completed).containsExactly("t1:" + a + "@2");
    }

    @Test
    void aStaleCompletionIsLoggedNotThrown() {
        UUID a = queue.add("t1", "a");
        queue.completeOutcome = SegmentQueue.CompleteOutcome.STALE;
        stt.script = request -> List.of(transcribed(a, "hola"));

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.completed).containsExactly("t1:" + a + "@1");
    }

    @Test
    void claimsAtMostTheSidecarsBatch() {
        for (int i = 0; i < 6; i++) {
            queue.add("t1", "s" + i);
        }
        stt.script = request -> request.segments().stream().<TranscriptionResult>map(s -> transcribed(s.id(), "")).toList();

        assertThat(worker.runOnce()).isEqualTo(ScriptedStt.BATCH);
        assertThat(stt.requests.get(0).segments()).hasSize(ScriptedStt.BATCH);
        assertThat(queue.pending()).hasSize(6 - ScriptedStt.BATCH);
    }

    @Test
    void twoTenantsSharingAnIdGoInSeparateRoundsAndEachIsReportedToItsOwnTenant() {
        UUID shared = queue.add("t1", "a");
        queue.add("t2", shared, "b");
        UUID other = queue.add("t1", "c");
        stt.script = request -> request.segments().stream().<TranscriptionResult>map(s -> transcribed(s.id(), "hola")).toList();

        assertThat(worker.runOnce()).isEqualTo(3);

        assertThat(stt.requests).hasSize(2);
        assertThat(stt.requests.get(0).segments()).extracting("id").containsExactly(shared, other);
        assertThat(stt.requests.get(1).segments()).extracting("id").containsExactly(shared);
        assertThat(queue.completed).containsExactlyInAnyOrder("t1:" + shared + "@1", "t1:" + other + "@1", "t2:" + shared + "@1");
        assertThat(queue.failed).isEmpty();
    }

    private static TranscriptionResult.Transcribed transcribed(UUID id, String text) {
        return new TranscriptionResult.Transcribed(id, text, "es", Duration.ofSeconds(10), List.of(), Duration.ofMillis(50));
    }

    /** A queue in memory with the contract's state machine, keyed by tenant and id as the real one is. */
    static final class FakeQueue implements SegmentQueue {
        private record Key(String tenantId, UUID id) {
        }

        private record Entry(Key key, byte[] audio, int attempt, boolean leased) {
        }

        final Map<Key, Entry> entries = new java.util.LinkedHashMap<>();
        final List<String> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<Duration> claimedWithLease = new ArrayList<>();
        int claims;
        CompleteOutcome completeOutcome = CompleteOutcome.COMPLETED;

        UUID add(String tenantId, String audio) {
            UUID id = new UUID(0x0190_0000_0000_7000L + entries.size(), 0x8000_0000_0000_0000L);
            add(tenantId, id, audio);
            return id;
        }

        void add(String tenantId, UUID id, String audio) {
            Key key = new Key(tenantId, id);
            entries.put(key, new Entry(key, audio.getBytes(), 0, false));
        }

        List<UUID> pending() {
            return entries.values().stream().filter(e -> !e.leased()).map(e -> e.key().id()).toList();
        }

        @Override
        public List<Job> claim(int max, Duration lease) {
            claims++;
            claimedWithLease.add(lease);
            List<Job> jobs = new ArrayList<>();
            for (Entry entry : List.copyOf(entries.values())) {
                if (jobs.size() == max) {
                    break;
                }
                if (!entry.leased()) {
                    Entry leased = new Entry(entry.key(), entry.audio(), entry.attempt() + 1, true);
                    entries.put(entry.key(), leased);
                    jobs.add(new Job(leased.key().id(), leased.key().tenantId(), UUID.randomUUID(), leased.attempt(),
                            Instant.now().plus(lease).truncatedTo(ChronoUnit.MICROS), leased.audio()));
                }
            }
            return jobs;
        }

        @Override
        public CompleteOutcome complete(String tenantId, UUID segmentId, int attempt) {
            completed.add(tenantId + ":" + segmentId + "@" + attempt);
            entries.remove(new Key(tenantId, segmentId));
            return completeOutcome;
        }

        @Override
        public FailOutcome fail(String tenantId, UUID segmentId, int attempt, String reason) {
            failed.add(tenantId + ":" + segmentId + "@" + attempt + ": " + reason);
            Key key = new Key(tenantId, segmentId);
            Entry entry = entries.get(key);
            entries.put(key, new Entry(key, entry.audio(), entry.attempt(), false));
            return FailOutcome.REQUEUED;
        }
    }

    static final class ScriptedStt implements SpeechToText {
        static final int BATCH = 4;

        final List<TranscriptionRequest> requests = new ArrayList<>();
        Function<TranscriptionRequest, List<TranscriptionResult>> script = request -> List.of();

        @Override
        public int maxBatchSize() {
            return BATCH;
        }

        @Override
        public List<TranscriptionResult> transcribe(TranscriptionRequest request) {
            requests.add(request);
            return script.apply(request);
        }
    }
}

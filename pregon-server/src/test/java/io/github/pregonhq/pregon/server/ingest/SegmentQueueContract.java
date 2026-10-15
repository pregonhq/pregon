package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.core.ingest.SegmentQueue.CompleteOutcome;
import io.github.pregonhq.pregon.core.ingest.SegmentQueue.FailOutcome;
import io.github.pregonhq.pregon.core.ingest.SegmentQueue.Job;
import org.junit.jupiter.api.Test;

/**
 * What every {@link SegmentQueue} must do (ADR-0024 §8, ADR-0025 §9), independent of how it is
 * stored. The implementation's test extends this class and provides the fixture hooks; it is
 * built with a {@link MutableClock} and a maximum of {@link #MAX_ATTEMPTS} attempts.
 */
public abstract class SegmentQueueContract {

    protected static final int MAX_ATTEMPTS = 3;
    protected static final Duration LEASE = Duration.ofMinutes(2);

    /** The queue under test, built with {@link #clock()} and {@link #MAX_ATTEMPTS}. */
    protected abstract SegmentQueue queue();

    protected abstract MutableClock clock();

    /** A new tenant with one active source; returns the tenant id. */
    protected abstract String newTenant();

    /** Records a segment with audio of {@code bytes} bytes and enqueues it, as ingestion would. */
    protected abstract UUID enqueue(String tenantId, int bytes);

    protected abstract String stateOf(UUID segmentId);

    protected abstract boolean audioExists(UUID segmentId);

    protected abstract int attemptOf(UUID segmentId);

    @Test
    void claimsInCaptureOrderAndLeasesWhatItHandsOut() {
        String tenant = newTenant();
        UUID first = enqueue(tenant, 3_000);
        UUID second = enqueue(tenant, 3_000);
        UUID third = enqueue(tenant, 3_000);

        List<Job> batch = queue().claim(2, LEASE);

        assertThat(batch).extracting(Job::segmentId).containsExactly(first, second);
        assertThat(batch).allSatisfy(job -> {
            assertThat(job.attempt()).isEqualTo(1);
            assertThat(job.tenantId()).isEqualTo(tenant);
            assertThat(job.leaseUntil()).isEqualTo(clock().instant().plus(LEASE));
            assertThat(job.audio()).hasSize(3_000);
        });
        assertThat(stateOf(first)).isEqualTo("LEASED");
        assertThat(stateOf(third)).isEqualTo("QUEUED");
        assertThat(queue().claim(5, LEASE)).extracting(Job::segmentId).containsExactly(third);
        assertThat(queue().claim(5, LEASE)).isEmpty();
    }

    @Test
    void aLeasedJobIsNotHandedOutAgainBeforeItsLeaseExpires() {
        UUID id = enqueue(newTenant(), 1_000);
        queue().claim(1, LEASE);

        clock().advance(LEASE.minusSeconds(1));

        assertThat(queue().claim(1, LEASE)).isEmpty();
        assertThat(stateOf(id)).isEqualTo("LEASED");
    }

    @Test
    void anExpiredLeaseReturnsTheJobWithTheNextAttempt() {
        UUID id = enqueue(newTenant(), 1_000);
        queue().claim(1, LEASE);

        clock().advance(LEASE.plusSeconds(1));
        List<Job> again = queue().claim(1, LEASE);

        assertThat(again).extracting(Job::segmentId).containsExactly(id);
        assertThat(again.get(0).attempt()).isEqualTo(2);
    }

    @Test
    void completingDeletesTheAudioAndKeepsTheRecord() {
        UUID id = enqueue(newTenant(), 1_000);
        Job job = queue().claim(1, LEASE).get(0);

        assertThat(queue().complete(id, job.attempt())).isEqualTo(CompleteOutcome.COMPLETED);

        assertThat(stateOf(id)).isEqualTo("TRANSCRIBED");
        assertThat(audioExists(id)).isFalse();
        assertThat(queue().claim(5, LEASE)).isEmpty();
    }

    @Test
    void aWorkerThatOutlivedItsLeaseCannotOverwriteTheNewOwner() {
        // The slow worker still holds attempt 1; the job was re-claimed as attempt 2 in the meantime.
        UUID id = enqueue(newTenant(), 1_000);
        queue().claim(1, LEASE);
        clock().advance(LEASE.plusSeconds(1));
        queue().claim(1, LEASE);

        assertThat(queue().complete(id, 1)).isEqualTo(CompleteOutcome.STALE);
        assertThat(queue().fail(id, 1, "late")).isEqualTo(FailOutcome.STALE);

        assertThat(stateOf(id)).isEqualTo("LEASED");
        assertThat(attemptOf(id)).isEqualTo(2);
        assertThat(audioExists(id)).isTrue();
        assertThat(queue().complete(id, 2)).isEqualTo(CompleteOutcome.COMPLETED);
    }

    @Test
    void completingTwiceIsStaleNotAnError() {
        UUID id = enqueue(newTenant(), 1_000);
        queue().claim(1, LEASE);

        assertThat(queue().complete(id, 1)).isEqualTo(CompleteOutcome.COMPLETED);
        assertThat(queue().complete(id, 1)).isEqualTo(CompleteOutcome.STALE);
        assertThat(stateOf(id)).isEqualTo("TRANSCRIBED");
    }

    @Test
    void failingRequeuesUntilTheLastAttemptThenFailsAndDeletesTheAudio() {
        UUID id = enqueue(newTenant(), 1_000);

        assertThat(queue().fail(id, queue().claim(1, LEASE).get(0).attempt(), "stt down")).isEqualTo(FailOutcome.REQUEUED);
        assertThat(stateOf(id)).isEqualTo("QUEUED");
        assertThat(queue().fail(id, queue().claim(1, LEASE).get(0).attempt(), "stt down")).isEqualTo(FailOutcome.REQUEUED);
        Job last = queue().claim(1, LEASE).get(0);
        assertThat(last.attempt()).isEqualTo(MAX_ATTEMPTS);

        assertThat(queue().fail(id, last.attempt(), "stt down")).isEqualTo(FailOutcome.FAILED);

        assertThat(stateOf(id)).isEqualTo("FAILED");
        assertThat(audioExists(id)).isFalse();
        assertThat(queue().claim(5, LEASE)).isEmpty();
    }

    @Test
    void aWorkerThatDiesWithoutReportingStillBurnsAnAttempt() {
        // Nobody ever calls fail: the attempt counted on claim is what stops the loop.
        UUID id = enqueue(newTenant(), 1_000);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThat(queue().claim(1, LEASE)).extracting(Job::attempt).containsExactly(attempt);
            clock().advance(LEASE.plusSeconds(1));
        }

        assertThat(queue().claim(1, LEASE)).isEmpty();

        assertThat(stateOf(id)).isEqualTo("FAILED");
        assertThat(audioExists(id)).isFalse();
    }

    @Test
    void aFailedSegmentIsNeverHandedOutAgain() {
        UUID id = enqueue(newTenant(), 1_000);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            queue().fail(id, queue().claim(1, LEASE).get(0).attempt(), "poison");
        }
        clock().advance(Duration.ofDays(1));

        assertThat(queue().claim(5, LEASE)).isEmpty();
        assertThat(queue().complete(id, MAX_ATTEMPTS)).isEqualTo(CompleteOutcome.STALE);
    }

    @Test
    void concurrentClaimsNeverHandTheSameJobToTwoWorkers() throws Exception {
        String tenant = newTenant();
        int jobs = 40;
        int workers = 8;
        for (int i = 0; i < jobs; i++) {
            enqueue(tenant, 500);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<List<Job>>> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            tasks.add(() -> {
                start.await();
                List<Job> mine = new ArrayList<>();
                List<Job> batch;
                while (!(batch = queue().claim(3, LEASE)).isEmpty()) {
                    mine.addAll(batch);
                }
                return mine;
            });
        }

        Set<UUID> seen = new HashSet<>();
        int total = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<List<Job>>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<List<Job>> future : futures) {
                for (Job job : future.get()) {
                    assertThat(seen.add(job.segmentId())).as("job handed out twice: " + job.segmentId()).isTrue();
                    total++;
                }
            }
        }

        assertThat(total).isEqualTo(jobs);
    }

    @Test
    void tenantsShareTheQueueButNeverEachOthersJobs() {
        String a = newTenant();
        String b = newTenant();
        UUID ofA = enqueue(a, 1_000);
        UUID ofB = enqueue(b, 1_000);

        List<Job> batch = queue().claim(5, LEASE);

        assertThat(batch).extracting(Job::segmentId).containsExactly(ofA, ofB);
        assertThat(batch).extracting(Job::tenantId).containsExactly(a, b);
    }
}

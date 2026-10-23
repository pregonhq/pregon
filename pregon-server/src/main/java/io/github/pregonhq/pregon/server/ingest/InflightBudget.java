package io.github.pregonhq.pregon.server.ingest;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-flight byte budget of ADR-0025 §4: how many upload bytes may be in memory at once, taken
 * for a request's {@code Content-Length} before its body is read and returned when the request
 * ends, however it ends. It bounds the heap under a storm whatever the thread count is.
 */
final class InflightBudget {

    private final long capacity;
    private final AtomicLong available;

    InflightBudget(long capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.available = new AtomicLong(capacity);
    }

    /** Takes {@code bytes} if they fit; never waits, because an agent is told to come back instead. */
    boolean tryAcquire(long bytes) {
        while (true) {
            long current = available.get();
            if (bytes > current) {
                return false;
            }
            if (available.compareAndSet(current, current - bytes)) {
                return true;
            }
        }
    }

    void release(long bytes) {
        available.addAndGet(bytes);
    }

    long capacity() {
        return capacity;
    }

    long available() {
        return available.get();
    }
}

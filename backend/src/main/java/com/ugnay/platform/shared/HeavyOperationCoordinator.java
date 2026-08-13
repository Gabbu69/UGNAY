package com.ugnay.platform.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates memory-intensive local work without creating another source of truth.
 * Durable jobs remain in MySQL while workers wait for a permit. Interactive ONNX
 * requests use {@link #tryAcquire(String)} so they can fall back to PARTIAL evidence
 * instead of making the UI hang on a constrained machine.
 */
@Component
public final class HeavyOperationCoordinator {
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public HeavyOperationCoordinator(
            @Value("${ugnay.heavy-operations.max-concurrent:2}") int maxConcurrent) {
        permits = new Semaphore(Math.max(1, Math.min(maxConcurrent, 4)), true);
    }

    public Optional<Lease> acquire(String operation) {
        if (depth.get() > 0) return Optional.of(nestedLease(operation));
        try {
            permits.acquire();
            active.incrementAndGet();
            depth.set(1);
            return Optional.of(new Lease(this, requiredName(operation), Thread.currentThread()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public Optional<Lease> tryAcquire(String operation) {
        if (depth.get() > 0) return Optional.of(nestedLease(operation));
        if (!permits.tryAcquire()) return Optional.empty();
        active.incrementAndGet();
        depth.set(1);
        return Optional.of(new Lease(this, requiredName(operation), Thread.currentThread()));
    }

    public boolean busy() {
        return active.get() > 0;
    }

    private Lease nestedLease(String operation) {
        depth.set(depth.get() + 1);
        return new Lease(this, requiredName(operation), Thread.currentThread());
    }

    private void release(Thread ownerThread) {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("A heavy-operation lease must close on its owning thread.");
        }
        int remaining = depth.get() - 1;
        if (remaining > 0) {
            depth.set(remaining);
            return;
        }
        depth.remove();
        active.decrementAndGet();
        permits.release();
    }

    private static String requiredName(String operation) {
        if (operation == null || operation.isBlank()) return "HEAVY_OPERATION";
        return operation.strip();
    }

    public static final class Lease implements AutoCloseable {
        private HeavyOperationCoordinator owner;
        private final String operation;
        private final Thread ownerThread;

        private Lease(HeavyOperationCoordinator owner, String operation, Thread ownerThread) {
            this.owner = owner;
            this.operation = operation;
            this.ownerThread = ownerThread;
        }

        public String operation() {
            return operation;
        }

        @Override
        public void close() {
            HeavyOperationCoordinator current = owner;
            if (current == null) return;
            owner = null;
            current.release(ownerThread);
        }
    }
}

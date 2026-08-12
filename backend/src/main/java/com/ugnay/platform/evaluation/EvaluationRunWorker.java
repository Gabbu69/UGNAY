package com.ugnay.platform.evaluation;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded local worker; durable QUEUED/RUNNING state remains in MySQL. */
@Component
final class EvaluationRunWorker implements ApplicationRunner {
    private final EvaluationService service;
    private final EvaluationTaskQueue executor;
    private final ScheduledExecutorService poller;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final long pollMillis;

    EvaluationRunWorker(EvaluationService service, EvaluationTaskQueue executor,
            @Value("${ugnay.evaluation.queue-poll-millis:1000}") long pollMillis) {
        this.service = service;
        this.executor = executor;
        this.pollMillis = Math.max(100, pollMillis);
        this.poller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ugnay-evaluation-durable-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void run(ApplicationArguments args) {
        service.recoverInterruptedRuns();
        poller.scheduleWithFixedDelay(this::pollSafely, 0, pollMillis, TimeUnit.MILLISECONDS);
    }

    void submit(UUID runId) {
        scheduleDrain();
    }

    void poll() {
        scheduleDrain();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException ignored) {
            // MySQL remains authoritative; the next bounded poll retries queued work.
        }
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return;
        if (!executor.tryExecute(this::drain)) drainScheduled.set(false);
    }

    private void drain() {
        try {
            while (true) {
                var pending = service.nextPendingRun();
                if (pending.isEmpty()) return;
                service.executeRun(pending.orElseThrow());
            }
        } finally {
            drainScheduled.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }
}

package com.ugnay.platform.warehouse;

import com.ugnay.platform.warehouse.WarehouseContracts.LoadView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded local worker; the database queue remains authoritative. */
@Component
class WarehouseRefreshWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseRefreshWorker.class);
    private final WarehouseRefreshQueueRepository queue;
    private final WarehouseService warehouse;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ugnay-warehouse-refresh").daemon(true).factory());
    private final AtomicBoolean scheduled = new AtomicBoolean();

    WarehouseRefreshWorker(WarehouseRefreshQueueRepository queue, WarehouseService warehouse) {
        this.queue = queue;
        this.warehouse = warehouse;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverAndStart() {
        queue.recoverInterrupted();
        signal();
    }

    void signal() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.submit(this::drain);
        } catch (RejectedExecutionException exception) {
            scheduled.set(false);
            if (!executor.isShutdown()) LOGGER.warn("Warehouse refresh worker could not be scheduled.", exception);
        }
    }

    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var claimed = queue.claimNext();
                if (claimed.isEmpty()) return;
                var request = claimed.orElseThrow();
                LoadView load = null;
                try {
                    load = warehouse.refreshQueued(request.actorEmail(), request.trigger());
                    UUID loadId = load.id();
                    if (loadId != null && ("PUBLISHED".equals(load.status()) || "UNCHANGED".equals(load.status()))) {
                        queue.complete(request.id(), loadId);
                    } else {
                        queue.fail(request.id(), loadId, load.failureReason());
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn("Durable warehouse refresh request {} failed safely.", request.id(), exception);
                    queue.fail(request.id(), load == null ? null : load.id(),
                            "Warehouse refresh request failed safely: " + exception.getClass().getSimpleName() + ".");
                }
            }
        } finally {
            scheduled.set(false);
            if (queue.hasQueued()) signal();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

package com.ugnay.platform.warehouse;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class WarehouseRefreshListener {
    private final WarehouseRefreshQueueRepository queue;
    private final WarehouseRefreshWorker worker;

    WarehouseRefreshListener(WarehouseRefreshQueueRepository queue, WarehouseRefreshWorker worker) {
        this.queue = queue;
        this.worker = worker;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void persistWithSourceChange(WarehouseRefreshRequested event) {
        queue.enqueue(event.actorEmail(), event.trigger());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void startAfterCommittedSourceChange(WarehouseRefreshRequested event) {
        worker.signal();
    }
}

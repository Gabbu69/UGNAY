package com.ugnay.platform.catalogue;

import java.util.concurrent.ExecutorService;

/** Bounded extraction runner that intentionally is not a general Spring Executor bean. */
final class DocumentExtractionExecutor implements AutoCloseable {
    private final ExecutorService delegate;

    DocumentExtractionExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    void execute(Runnable task) {
        delegate.execute(task);
    }

    @Override
    public void close() {
        delegate.shutdownNow();
    }
}

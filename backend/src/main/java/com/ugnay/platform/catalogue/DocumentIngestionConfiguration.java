package com.ugnay.platform.catalogue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
class DocumentIngestionConfiguration {
    @Bean(destroyMethod = "close")
    DocumentExtractionExecutor documentExtractionExecutor(
            @Value("${ugnay.ingestion.extraction-workers:2}") int workers,
            @Value("${ugnay.ingestion.extraction-queue-capacity:24}") int queueCapacity) {
        int boundedWorkers = Math.max(1, Math.min(workers, 8));
        int boundedQueue = Math.max(1, Math.min(queueCapacity, 500));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "ugnay-document-extraction-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService delegate = new ThreadPoolExecutor(boundedWorkers, boundedWorkers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(boundedQueue), threads, new ThreadPoolExecutor.AbortPolicy());
        return new DocumentExtractionExecutor(delegate);
    }
}

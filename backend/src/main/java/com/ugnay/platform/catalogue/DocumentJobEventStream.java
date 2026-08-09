package com.ugnay.platform.catalogue;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Component
final class DocumentJobEventStream {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    SseEmitter subscribe(UUID jobId, Supplier<DocumentImportJob> currentJob) throws IOException {
        SseEmitter emitter = new SseEmitter(120_000L);
        CopyOnWriteArrayList<SseEmitter> jobSubscribers = subscribers.computeIfAbsent(jobId, ignored -> new CopyOnWriteArrayList<>());
        jobSubscribers.add(emitter);
        Runnable remove = () -> remove(jobId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        try {
            DocumentImportJob initial = currentJob.get();
            send(emitter, initial);
            if (initial.terminal()) {
                remove.run();
                emitter.complete();
            }
        } catch (RuntimeException | IOException exception) {
            remove.run();
            throw exception;
        }
        return emitter;
    }

    void publish(DocumentImportJob job) {
        List<SseEmitter> emitters = subscribers.getOrDefault(job.jobId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, job);
                if (job.terminal()) emitter.complete();
            } catch (IOException | IllegalStateException exception) {
                emitter.completeWithError(exception);
            } finally {
                if (job.terminal()) remove(job.jobId(), emitter);
            }
        }
    }

    private static void send(SseEmitter emitter, DocumentImportJob job) throws IOException {
        emitter.send(SseEmitter.event().id(job.status() + "-" + job.attemptCount())
                .name("progress").data(job));
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(jobId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) subscribers.remove(jobId, emitters);
    }
}

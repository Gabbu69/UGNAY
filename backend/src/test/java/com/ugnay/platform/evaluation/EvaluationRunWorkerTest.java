package com.ugnay.platform.evaluation;

import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationRunWorkerTest {

    @Test
    void queueSaturationLeavesDurableRowsQueuedAndNextPollDrainsEveryRun() {
        EvaluationService service = mock(EvaluationService.class);
        EvaluationTaskQueue queue = mock(EvaluationTaskQueue.class);
        EvaluationRunWorker worker = new EvaluationRunWorker(service, queue, 60_000);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        when(queue.tryExecute(any(Runnable.class)))
                .thenReturn(false)
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return true;
                });
        when(service.nextPendingRun())
                .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());

        try {
            worker.submit(first);
            verify(service, never()).executeRun(any(UUID.class));

            worker.poll();

            var order = inOrder(service);
            order.verify(service).executeRun(first);
            order.verify(service).executeRun(second);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void acceptedResponseReloadsThePersistedRunAfterScheduling() {
        EvaluationService service = mock(EvaluationService.class);
        EvaluationRunWorker worker = mock(EvaluationRunWorker.class);
        EvaluationController controller = new EvaluationController(service, worker);
        UUID datasetId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        EvaluationModels.RunView queued = run(runId, datasetId, EvaluationModels.RunStatus.QUEUED);
        EvaluationModels.RunView current = run(runId, datasetId, EvaluationModels.RunStatus.RUNNING);
        when(service.queueRun(datasetId, "adviser@ugnay.local")).thenReturn(queued);
        when(service.run(runId)).thenReturn(current);
        Principal principal = () -> "adviser@ugnay.local";

        var response = controller.startRun(new EvaluationController.RunRequest(datasetId), principal);

        assertThat(response.getBody()).isSameAs(current);
        var order = inOrder(service, worker);
        order.verify(service).queueRun(datasetId, "adviser@ugnay.local");
        order.verify(worker).submit(runId);
        order.verify(service).run(runId);
    }

    private static EvaluationModels.RunView run(UUID runId, UUID datasetId, EvaluationModels.RunStatus status) {
        return new EvaluationModels.RunView(runId, datasetId, status, EvaluationModels.ComparabilityStatus.UNAVAILABLE,
                5, List.of(1, 3, 5, 10), 5, 17L, "test-build", "environment-sha", "run-sha", null,
                EvaluationModels.ReportStatus.PRIVATE, Instant.parse("2026-08-11T00:00:00Z"),
                status == EvaluationModels.RunStatus.RUNNING ? Instant.parse("2026-08-11T00:00:01Z") : null,
                null, null);
    }
}

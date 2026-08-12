package com.ugnay.platform.evaluation;

import jakarta.annotation.PreDestroy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Module-private bounded executor that does not replace Boot's application task executor. */
@Component
final class EvaluationTaskQueue {
    private final ThreadPoolTaskExecutor executor;

    EvaluationTaskQueue() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("ugnay-evaluation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
    }

    boolean tryExecute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException exception) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }
}

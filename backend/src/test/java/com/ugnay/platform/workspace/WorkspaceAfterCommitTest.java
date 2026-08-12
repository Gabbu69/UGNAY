package com.ugnay.platform.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-after-commit-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@ActiveProfiles("test")
class WorkspaceAfterCommitTest {
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void cacheMutationRunsOnlyAfterCommitAndNeverAfterRollback() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicBoolean committed = new AtomicBoolean();

        transactions.executeWithoutResult(status -> {
            WorkspaceService.afterCommit(() -> committed.set(true));
            assertThat(committed).isFalse();
        });
        assertThat(committed).isTrue();

        AtomicBoolean rolledBack = new AtomicBoolean();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            WorkspaceService.afterCommit(() -> rolledBack.set(true));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rolledBack).isFalse();
    }
}

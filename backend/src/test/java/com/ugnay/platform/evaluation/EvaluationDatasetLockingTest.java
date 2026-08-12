package com.ugnay.platform.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ugnay-evaluation-lock-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR",
        "ugnay.dataset-mode=EMPTY",
        "ugnay.evaluation.queue-poll-millis=60000"
})
@ActiveProfiles("test")
class EvaluationDatasetLockingTest {
    @Autowired EvaluationService service;
    @Autowired JdbcEvaluationRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void freezeWaitsForTheDraftLockAndValidatesCommittedMutations() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String reviewerOne = "lock-reviewer-one-" + suffix + "@ugnay.local";
        String reviewerTwo = "lock-reviewer-two-" + suffix + "@ugnay.local";
        insertAccount(reviewerOne);
        insertAccount(reviewerTwo);
        UUID studyId = insertStudy("Locking corpus " + suffix);
        var dataset = service.createDataset("Locking fixture " + suffix, null, List.of(studyId), "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), structuredQuery("BASE-" + suffix), "admin@ugnay.local");
        service.judge(query.id(), studyId, 2, "The first independent reviewer found relevant evidence.", reviewerOne);
        service.judge(query.id(), studyId, 2, "The second independent reviewer found relevant evidence.", reviewerTwo);
        service.adjudicate(query.id(), studyId, 2, "The two reviews support an adjudicated relevant result.", "admin@ugnay.local");

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var mutation = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lockDraft(dataset.versionId());
                rowLocked.countDown();
                await(releaseMutation);
                service.addQuery(dataset.versionId(), structuredQuery("LATE-" + suffix), "admin@ugnay.local");
            }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            var freeze = pool.submit(() -> service.freeze(dataset.versionId(), "admin@ugnay.local"));
            assertThatThrownBy(() -> freeze.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseMutation.countDown();
            mutation.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> freeze.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Query LATE-" + suffix + " needs at least one adjudicated relevant study before freeze.");
        } finally {
            releaseMutation.countDown();
        }

        assertThat(service.dataset(dataset.versionId()).status()).isEqualTo(EvaluationModels.DatasetStatus.DRAFT);
        assertThat(service.queries(dataset.versionId())).hasSize(2);
    }

    private static EvaluationService.StructuredQuery structuredQuery(String key) {
        return new EvaluationService.StructuredQuery(key, EvaluationModels.QuerySplit.TEST, "Research evidence query",
                "A campus unit needs prior research evidence.", List.of("Find related studies"),
                "An evidence-centred research platform", "Design science", "Historical research catalogue",
                "Java and MySQL", "Students and advisers", "Campus unit", "University campus");
    }

    private void insertAccount(String email) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), department, email, "Lock Reviewer", "ACTIVE", 0, Timestamp.from(Instant.now()));
    }

    private UUID insertStudy(String title) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO studies(id,department_id,title,lifecycle_status,visibility,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(id), department, title, "PUBLISHED", "PUBLIC", 0, Timestamp.from(Instant.now()));
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for the lock test.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock test was interrupted.", exception);
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

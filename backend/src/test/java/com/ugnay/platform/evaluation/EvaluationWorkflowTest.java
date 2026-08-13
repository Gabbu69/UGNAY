package com.ugnay.platform.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ugnay-evaluation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR",
        "ugnay.dataset-mode=EMPTY",
        "ugnay.evaluation.queue-poll-millis=60000",
        "ugnay.discovery.model-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "ugnay.discovery.tokenizer-sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "ugnay.discovery.algorithm-version=hybrid-test-v1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EvaluationWorkflowTest {
    @Autowired EvaluationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void freezesDoubleReviewedQrelsAndPersistsAComparableExperimentManifest() {
        String suffix = UUID.randomUUID().toString();
        String reviewerOne = "reviewer-one-" + suffix + "@ugnay.local";
        String reviewerTwo = "reviewer-two-" + suffix + "@ugnay.local";
        insertAccount(reviewerOne);
        insertAccount(reviewerTwo);
        UUID studyId = insertStudy("Evaluation corpus " + suffix);

        var dataset = service.createDataset("Retrieval fixture " + suffix, "Synthetic application seed used only by this test.",
                List.of(studyId), "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), new EvaluationService.StructuredQuery(
                "Q-" + suffix, EvaluationModels.QuerySplit.TEST, "Campus research evidence",
                "A campus office needs evidence-backed continuity.", List.of("Find related prior studies"),
                "A local research continuity platform", "Design science", "Historical thesis catalogue",
                "Java MySQL research retrieval", "Students and advisers", "Campus office", "University campus"),
                "admin@ugnay.local");

        var first = service.judge(query.id(), studyId, 2, "The study addresses part of the stated research need.", reviewerOne);
        var revision = service.judge(query.id(), studyId, 3, "The study strongly addresses the final structured query.", reviewerOne);
        service.judge(query.id(), studyId, 3, "The title and problem evidence are strongly relevant.", reviewerTwo);
        assertThat(first.revision()).isOne();
        assertThat(revision.revision()).isEqualTo(2);

        service.adjudicate(query.id(), studyId, 3, "Two independent reviews support a strongly relevant qrel.", "admin@ugnay.local");
        var frozen = service.freeze(dataset.versionId(), "admin@ugnay.local");
        assertThat(frozen.status()).isEqualTo(EvaluationModels.DatasetStatus.FROZEN);
        assertThat(frozen.datasetSha256()).hasSize(64);
        assertThatThrownBy(() -> service.judge(query.id(), studyId, 1, "This late edit must be rejected.", reviewerOne))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Frozen");

        var queued = service.queueRun(dataset.versionId(), "admin@ugnay.local");
        service.executeRun(queued.id());
        var run = service.run(queued.id());
        var report = service.report(queued.id(), true);

        assertThat(run.status()).isEqualTo(EvaluationModels.RunStatus.PARTIAL);
        assertThat(run.comparability()).isEqualTo(EvaluationModels.ComparabilityStatus.PARTIAL);
        assertThat(report.algorithms()).hasSize(4);
        assertThat(report.algorithms()).anySatisfy(algorithm -> {
            assertThat(algorithm.algorithm()).isEqualTo(EvaluationModels.Algorithm.SEMANTIC_E5);
            assertThat(algorithm.status()).isEqualTo(EvaluationModels.RunStatus.UNAVAILABLE);
            assertThat(algorithm.aggregateMetrics()).allSatisfy(metric -> {
                assertThat(metric.status()).isEqualTo(EvaluationModels.MetricStatus.UNAVAILABLE);
                assertThat(metric.precision()).isNull();
            });
        });
        assertThat(report.algorithms()).filteredOn(value -> value.algorithm() == EvaluationModels.Algorithm.LEXICAL_KEYWORD)
                .singleElement().satisfies(value -> assertThat(value.aggregateMetrics()).hasSize(4));
        assertThat(report.interpretationBoundary()).contains("cannot approve a thesis");
        assertThat(report.manifest().toString())
                .contains("modelSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contains("tokenizerSha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .contains("discoveryConfigurationVersion=hybrid-test-v1")
                .doesNotContain("model-path", "tokenizer-path");
        assertThat(service.csvReport(queued.id())).contains("UNAVAILABLE").contains("TF_IDF_COSINE_V1");
    }

    @Test
    void missingRelevanceGradeIsRejectedBeforeServiceExecution() throws Exception {
        mvc.perform(post("/api/v1/evaluation/queries/" + UUID.randomUUID() + "/judgments")
                        .with(user("adviser@ugnay.local").roles("ADVISER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyId":"%s","rationale":"A required relevance grade was intentionally omitted."}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAdjudicationUntilTwoDistinctReviewersExist() {
        String suffix = UUID.randomUUID().toString();
        String reviewer = "single-reviewer-" + suffix + "@ugnay.local";
        insertAccount(reviewer);
        UUID studyId = insertStudy("Incomplete corpus " + suffix);
        var dataset = service.createDataset("Incomplete qrels " + suffix, null, List.of(studyId), "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), new EvaluationService.StructuredQuery("Q-" + suffix,
                EvaluationModels.QuerySplit.DEV, "Research query", "Evidence problem", List.of("Assess evidence"),
                "", "", "", "research", "", "", ""), "admin@ugnay.local");
        service.judge(query.id(), studyId, 2, "One reviewer cannot establish adjudicated truth alone.", reviewer);

        assertThatThrownBy(() -> service.adjudicate(query.id(), studyId, 2,
                "This must remain unavailable until independent review.", "admin@ugnay.local"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two distinct reviewers");
    }

    @Test
    void adjudicatorCannotAlsoSupplyOneOfTheCurrentReviews() {
        String suffix = UUID.randomUUID().toString();
        String independentReviewer = "independent-reviewer-" + suffix + "@ugnay.local";
        insertAccount(independentReviewer);
        UUID studyId = insertStudy("Independent adjudication " + suffix);
        var dataset = service.createDataset("Independent qrels " + suffix, null,
                List.of(studyId), "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), new EvaluationService.StructuredQuery(
                "Q-" + suffix, EvaluationModels.QuerySplit.TEST, "Independent research judgment",
                "Two reviews must remain distinct from the final adjudicator.", List.of("Protect qrel independence"),
                "", "", "", "research", "", "", ""), "admin@ugnay.local");
        service.judge(query.id(), studyId, 2, "The bootstrap coordinator supplied the first current review.",
                "admin@ugnay.local");
        service.judge(query.id(), studyId, 2, "An independent account supplied the second current review.",
                independentReviewer);

        assertThatThrownBy(() -> service.adjudicate(query.id(), studyId, 2,
                "The same coordinator must not adjudicate evidence they reviewed.", "admin@ugnay.local"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("independent");
    }

    private void insertAccount(String email) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), department, email, "Evaluation Reviewer", "ACTIVE", 0, Timestamp.from(Instant.now()));
    }

    private UUID insertStudy(String title) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO studies(id,department_id,title,lifecycle_status,visibility,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(id), department, title, "PUBLISHED", "PUBLIC", 0, Timestamp.from(Instant.now()));
        return id;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer value = ByteBuffer.wrap(bytes);
        return new UUID(value.getLong(), value.getLong());
    }
}

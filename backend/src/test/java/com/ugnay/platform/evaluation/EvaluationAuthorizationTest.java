package com.ugnay.platform.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ugnay-evaluation-authorization-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR",
        "ugnay.dataset-mode=EMPTY",
        "ugnay.evaluation.queue-poll-millis=60000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EvaluationAuthorizationTest {
    private static final String UNAVAILABLE = "One or more requested studies are unavailable for evaluation.";

    @Autowired EvaluationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void corpusCreationAdmitsOnlyGloballyShareableStudiesWithoutRevealingWhyOthersAreUnavailable() {
        String suffix = UUID.randomUUID().toString();
        UUID publicStudy = insertStudy("Public corpus " + suffix, "PUBLIC", "2025");
        UUID campusStudy = insertStudy("Campus corpus " + suffix, "CAMPUS", "2024");
        UUID internalStudy = insertStudy("Internal corpus " + suffix, "INTERNAL", "2023");
        UUID restrictedStudy = insertStudy("Restricted corpus " + suffix, "RESTRICTED", "2022");

        var selected = service.createDataset("Shareable selection " + suffix, null,
                List.of(publicStudy, campusStudy), "admin@ugnay.local");
        assertThat(selected.corpusSize()).isEqualTo(2);

        for (UUID unavailable : List.of(internalStudy, restrictedStudy, UUID.randomUUID())) {
            assertThatThrownBy(() -> service.createDataset("Unavailable selection " + UUID.randomUUID(), null,
                    List.of(unavailable), "admin@ugnay.local"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(UNAVAILABLE);
        }

        var defaultDataset = service.createDataset("Default shareable selection " + suffix, null, null,
                "admin@ugnay.local");
        assertThat(corpusMembership(defaultDataset.versionId(), publicStudy)).isOne();
        assertThat(corpusMembership(defaultDataset.versionId(), campusStudy)).isOne();
        assertThat(corpusMembership(defaultDataset.versionId(), internalStudy)).isZero();
        assertThat(corpusMembership(defaultDataset.versionId(), restrictedStudy)).isZero();
    }

    @Test
    void paginatedDraftLedgerShowsOnlyTheActorsJudgmentAndCompleteness() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String reviewerOne = "ledger-one-" + suffix + "@ugnay.local";
        String reviewerTwo = "ledger-two-" + suffix + "@ugnay.local";
        insertAccount(reviewerOne);
        insertAccount(reviewerTwo);
        UUID judgedStudy = insertStudy("Frozen ledger title " + suffix, "PUBLIC", "2025");
        UUID pendingStudy = insertStudy("Pending ledger title " + suffix, "CAMPUS", null);
        var dataset = service.createDataset("Ledger fixture " + suffix, null,
                List.of(judgedStudy, pendingStudy), "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), structuredQuery("LEDGER-" + suffix), "admin@ugnay.local");
        service.judge(query.id(), judgedStudy, 2, "The actor's first evidence grade.", reviewerOne);
        service.judge(query.id(), judgedStudy, 3, "The actor's current evidence grade.", reviewerOne);
        service.judge(query.id(), judgedStudy, 3, "An independent reviewer agrees.", reviewerTwo);
        service.adjudicate(query.id(), judgedStudy, 3, "The current independent reviews are adjudicated.",
                "admin@ugnay.local");

        var ledger = service.corpusReview(dataset.versionId(), query.id(), reviewerOne, 0, 10);
        assertThat(ledger.totalElements()).isEqualTo(2);
        assertThat(ledger.items()).filteredOn(item -> item.studyId().equals(judgedStudy)).singleElement()
                .satisfies(item -> {
                    assertThat(item.title()).isEqualTo("Frozen ledger title " + suffix);
                    assertThat(item.academicYear()).isEqualTo("2025");
                    assertThat(item.department()).isNotBlank();
                    assertThat(item.currentActorJudgment().relevanceGrade()).isEqualTo(3);
                    assertThat(item.currentActorJudgment().revision()).isEqualTo(2);
                    assertThat(item.distinctReviewerCount()).isEqualTo(2);
                    assertThat(item.doubleReviewed()).isTrue();
                    assertThat(item.adjudicatedGrade()).isEqualTo(3);
                    assertThat(item.adjudicationCurrent()).isTrue();
                });
        assertThat(ledger.items()).filteredOn(item -> item.studyId().equals(pendingStudy)).singleElement()
                .satisfies(item -> {
                    assertThat(item.academicYear()).isNull();
                    assertThat(item.currentActorJudgment()).isNull();
                    assertThat(item.distinctReviewerCount()).isZero();
                    assertThat(item.adjudicatedGrade()).isNull();
                });

        String route = "/api/v1/evaluation/datasets/" + dataset.versionId() + "/queries/" + query.id() + "/corpus";
        mvc.perform(get(route).queryParam("page", "0").queryParam("size", "1")
                        .with(user(reviewerOne).roles("ADVISER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(route).with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/evaluation/datasets/" + dataset.versionId() + "/queries")
                        .with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyExplicitlyPublishedTerminalReportsAreVisibleToStudentsAndVisibilityDowngradesFailClosed()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String reviewerOne = "publish-one-" + suffix + "@ugnay.local";
        String reviewerTwo = "publish-two-" + suffix + "@ugnay.local";
        insertAccount(reviewerOne);
        insertAccount(reviewerTwo);
        UUID studyId = insertStudy("Publishable corpus " + suffix, "PUBLIC", "2025");
        var dataset = service.createDataset("Publication fixture " + suffix, null, List.of(studyId),
                "admin@ugnay.local");
        var query = service.addQuery(dataset.versionId(), structuredQuery("PUBLISH-" + suffix),
                "admin@ugnay.local");
        service.judge(query.id(), studyId, 3, "The first reviewer found strong relevance.", reviewerOne);
        service.judge(query.id(), studyId, 3, "The independent reviewer found strong relevance.", reviewerTwo);
        service.adjudicate(query.id(), studyId, 3, "The independent judgments support this qrel.",
                "admin@ugnay.local");
        service.freeze(dataset.versionId(), "admin@ugnay.local");
        var queued = service.queueRun(dataset.versionId(), "admin@ugnay.local");
        service.executeRun(queued.id());
        assertThat(service.run(queued.id()).status()).isIn(EvaluationModels.RunStatus.COMPLETED,
                EvaluationModels.RunStatus.PARTIAL);

        String runRoute = "/api/v1/evaluation/runs/" + queued.id();
        mvc.perform(get(runRoute).with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get(runRoute + "/report").with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get(runRoute + "/report.csv").with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get(runRoute).with(user("adviser@ugnay.local").roles("ADVISER")))
                .andExpect(status().isOk());
        mvc.perform(post(runRoute + "/publish").with(csrf())
                        .with(user("adviser@ugnay.local").roles("ADVISER")))
                .andExpect(status().isForbidden());

        mvc.perform(post(runRoute + "/publish").with(csrf())
                        .with(user("admin@ugnay.local").roles("COORDINATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        mvc.perform(get(runRoute).with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("PUBLISHED"));
        mvc.perform(get(runRoute + "/report").with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithms.length()").value(4));
        mvc.perform(get(runRoute + "/report.csv").with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TF_IDF_COSINE_V1")));
        mvc.perform(get("/api/v1/evaluation/reports/published")
                        .with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(queued.id().toString())));

        var secondQueued = service.queueRun(dataset.versionId(), "admin@ugnay.local");
        assertThatThrownBy(() -> service.publishRun(secondQueued.id(), "admin@ugnay.local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed or partial terminal");

        jdbc.update("UPDATE studies SET visibility='RESTRICTED' WHERE id=?", bytes(studyId));
        mvc.perform(get(runRoute + "/report").with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/evaluation/reports/published")
                        .with(user("student@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(queued.id().toString()))));
        mvc.perform(get(runRoute).with(user("adviser@ugnay.local").roles("ADVISER")))
                .andExpect(status().isOk());
    }

    private int corpusMembership(UUID versionId, UUID studyId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_corpus_items WHERE dataset_version_id=? AND study_id=?",
                Integer.class, bytes(versionId), bytes(studyId));
    }

    private EvaluationService.StructuredQuery structuredQuery(String key) {
        return new EvaluationService.StructuredQuery(key, EvaluationModels.QuerySplit.TEST,
                "Research evidence query", "A campus unit needs prior research evidence.",
                List.of("Find related studies"), "An evidence-centred research platform", "Design science",
                "Historical research catalogue", "Java and MySQL", "Students and advisers", "Campus unit",
                "University campus");
    }

    private void insertAccount(String email) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), department, email, "Evaluation Reviewer", "ACTIVE", 0,
                Timestamp.from(Instant.now()));
    }

    private UUID insertStudy(String title, String visibility, String academicYear) {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments ORDER BY code LIMIT 1", byte[].class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO studies(id,department_id,title,academic_year,lifecycle_status,visibility,row_version,created_at) VALUES(?,?,?,?,?,?,?,?)",
                bytes(id), department, title, academicYear, "PUBLISHED", visibility, 0, Timestamp.from(Instant.now()));
        return id;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

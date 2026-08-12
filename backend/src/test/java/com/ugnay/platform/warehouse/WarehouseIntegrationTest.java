package com.ugnay.platform.warehouse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ugnay-warehouse-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR",
        "ugnay.dataset-mode=EMPTY",
        "ugnay.warehouse.max-source-studies=100",
        "ugnay.warehouse.max-source-rows=5000"
})
class WarehouseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WarehouseRefreshQueueRepository refreshQueue;

    @Test
    void durableRefreshRequestsRecoverAfterAnInterruptedWorker() {
        java.util.UUID requestId = refreshQueue.enqueue("admin@ugnay.local",
                WarehouseRefreshRequested.Trigger.CATALOGUE_PUBLICATION);

        assertThat(jdbc.queryForObject("SELECT request_status FROM warehouse_refresh_requests WHERE id=?",
                String.class, uuidBytes(requestId.toString()))).isEqualTo("QUEUED");
        assertThat(refreshQueue.claimNext().orElseThrow().id()).isEqualTo(requestId);
        assertThat(jdbc.queryForObject("SELECT request_status FROM warehouse_refresh_requests WHERE id=?",
                String.class, uuidBytes(requestId.toString()))).isEqualTo("RUNNING");

        refreshQueue.recoverInterrupted();

        assertThat(jdbc.queryForObject("SELECT request_status FROM warehouse_refresh_requests WHERE id=?",
                String.class, uuidBytes(requestId.toString()))).isEqualTo("QUEUED");
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void publishesAllSixStagesReportsInvalidYearsAndCoalescesIdenticalSources() throws Exception {
        mvc.perform(get("/api/v1/warehouse/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentStatus").value("UNASSESSED"))
                .andExpect(jsonPath("$.studiesPerYear").isEmpty());

        insertResearchCorpus();

        String firstBody = mvc.perform(post("/api/v1/warehouse/refresh").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.assessmentStatus").value("ASSESSED"))
                .andExpect(jsonPath("$.stages.length()").value(6))
                .andExpect(jsonPath("$.stages[0].stage").value("COLLECT"))
                .andExpect(jsonPath("$.stages[5].stage").value("ANALYZE"))
                .andExpect(jsonPath("$.stages[5].status").value("COMPLETED"))
                .andExpect(jsonPath("$.quality.byCode.ACADEMIC_YEAR_INVALID").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = json.readTree(firstBody);
        String snapshotId = first.get("snapshotId").asText();

        mvc.perform(get("/api/v1/warehouse/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId))
                .andExpect(jsonPath("$.visibleStudyCount").value(first.get("acceptedCount").asInt()))
                .andExpect(jsonPath("$.unavailableYearCount").value(1))
                .andExpect(jsonPath("$.commonResearchAreas").isEmpty())
                .andExpect(jsonPath("$.repeatedTopics[0].label").value("Offline Systems"))
                .andExpect(jsonPath("$.repeatedTopics[0].studyCount").value(2));

        mvc.perform(get("/api/v1/warehouse/analytics").with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleStudyCount").value(2))
                .andExpect(jsonPath("$.sourceStudyCount").value(2))
                .andExpect(jsonPath("$.quality.assessmentStatus").value("PARTIAL"));

        mvc.perform(get("/api/v1/warehouse/analytics.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STUDIES_PER_YEAR")));

        mvc.perform(get("/api/v1/warehouse/continuation-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentStatus").value("ASSESSED"))
                .andExpect(jsonPath("$.items").isArray());

        mvc.perform(post("/api/v1/warehouse/refresh").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNCHANGED"))
                .andExpect(jsonPath("$.snapshotId").value(snapshotId))
                .andExpect(jsonPath("$.stages[1].status").value("SKIPPED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse_snapshots WHERE snapshot_status='PUBLISHED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dw_study_dimensions WHERE snapshot_id=?", Integer.class,
                uuidBytes(snapshotId))).isEqualTo(first.get("acceptedCount").asInt());
        assertThat(jdbc.queryForObject("SELECT results_text FROM dw_study_dimensions WHERE snapshot_id=? AND institutional_code='CICS-2025-001'",
                String.class, uuidBytes(snapshotId))).isEqualTo("Observed farmer outcomes.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dw_study_version_dimensions WHERE snapshot_id=?", Integer.class,
                uuidBytes(snapshotId))).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dw_continuation_facts WHERE snapshot_id=?", Integer.class,
                uuidBytes(snapshotId))).isOne();
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void nonCuratorCannotRefreshOrReadCuratorLedger() throws Exception {
        mvc.perform(post("/api/v1/warehouse/refresh").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/warehouse/loads/latest")).andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void anonymousDemoReadsCannotInspectWarehouse() throws Exception {
        mvc.perform(get("/api/v1/warehouse/analytics")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/warehouse/continuation-history")).andExpect(status().isForbidden());
    }

    private static byte[] uuidBytes(String value) {
        java.util.UUID id = java.util.UUID.fromString(value);
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private void insertResearchCorpus() {
        byte[] department = jdbc.queryForObject("SELECT id FROM departments WHERE code='CICS'", byte[].class);
        java.time.Instant now = java.time.Instant.parse("2026-08-11T00:00:00Z");
        byte[] first = uuidBytes("10000000-0000-0000-0000-000000000001");
        byte[] second = uuidBytes("10000000-0000-0000-0000-000000000002");
        byte[] restricted = uuidBytes("10000000-0000-0000-0000-000000000003");
        insertStudy(first, department, "CICS-2025-001", "Offline Agriculture Evidence", "2024-2025", "CAMPUS", "Observed farmer outcomes.", now);
        insertStudy(second, department, "CICS-2026-002", "Offline Community Evidence", "2025/2026", "CAMPUS", null, now);
        insertStudy(restricted, department, "CICS-2024-003", "Restricted Clinical Evidence", "2023-2024", "RESTRICTED", null, now);

        byte[] topic = uuidBytes("20000000-0000-0000-0000-000000000001");
        jdbc.update("INSERT INTO taxonomy_terms(id,term_type,canonical_label,filipino_label,active) VALUES(?,?,?,?,?)",
                topic, "KEYWORD", "Offline Systems", null, true);
        jdbc.update("INSERT INTO study_terms(study_id,term_id) VALUES(?,?)", first, topic);
        jdbc.update("INSERT INTO study_terms(study_id,term_id) VALUES(?,?)", second, topic);
        jdbc.update("INSERT INTO study_objectives(id,study_id,objective_order,statement_text) VALUES(?,?,?,?)",
                uuidBytes("30000000-0000-0000-0000-000000000001"), first, 0, "Evaluate offline research continuity.");
        jdbc.update("INSERT INTO study_metadata_versions(id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_by,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                uuidBytes("40000000-0000-0000-0000-000000000001"), first, 1, "LEGACY_SNAPSHOT", "a".repeat(64),
                "{\"title\":\"Offline Agriculture Evidence\"}", null, java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO study_relationships(id,source_study_id,target_study_id,relationship_type,rationale,created_at) VALUES(?,?,?,?,?,?)",
                uuidBytes("50000000-0000-0000-0000-000000000001"), first, second, "CONTINUES",
                "Recorded continuation relationship.", java.sql.Timestamp.from(now));
    }

    private void insertStudy(byte[] id, byte[] department, String code, String title, String academicYear,
            String visibility, String results, java.time.Instant createdAt) {
        jdbc.update("INSERT INTO studies(id,department_id,source_project_id,institutional_code,doi,title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,academic_year,lifecycle_status,visibility,repository_identifier,published_at,archived_at,row_version,created_at,program_name,results_text,completion_year) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, department, null, code, null, title, "Recorded abstract.", "Recorded problem.", "Recorded method.", null,
                null, null, null, null, null, "offline systems", academicYear, "COMPLETED", visibility, null,
                java.sql.Timestamp.from(createdAt), null, 0, java.sql.Timestamp.from(createdAt), "BSCS", results, null);
    }
}

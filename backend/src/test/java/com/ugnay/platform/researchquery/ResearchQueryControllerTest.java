package com.ugnay.platform.researchquery;

import com.ugnay.platform.shared.JdbcAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchQueryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcAuditService audit;

    @Test
    void grammarAndExecutionRequireAuthenticationAndExecutionRequiresCsrf() throws Exception {
        mvc.perform(get("/api/v1/research-queries/grammar")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/research-queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FIND THESIS\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/research-queries/execute")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("admin@ugnay.local").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"FIND THESIS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void exposesGrammarAndEveryInterpreterStageForAValidQuery() throws Exception {
        mvc.perform(get("/api/v1/research-queries/grammar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("ugnay-rql-1.0.0"))
                .andExpect(jsonPath("$.ebnf").value(org.hamcrest.Matchers.containsString("query")))
                .andExpect(jsonPath("$.limits.maximumResults").value(100));

        mvc.perform(post("/api/v1/warehouse/refresh").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("admin@ugnay.local").roles("CURATOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotId").isNotEmpty());

        String body = """
                {"source":"FIND THESIS WHERE TOPIC CONTAINS \\"flood\\" USING LEXICAL ORDER BY RELEVANCE DESC LIMIT 5",
                 "includeTrace":true}
                """;
        mvc.perform(post("/api/v1/research-queries/execute").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.tokens[0].type").value("FIND"))
                .andExpect(jsonPath("$.ast.kind").value("QUERY"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.interpretedAction.executor").value("ALLOW_LISTED_AST_INTERPRETER_WITH_BOUND_JDBC"))
                .andExpect(jsonPath("$.algorithmVersion").value("LEXICAL_KEYWORD_V1"))
                .andExpect(jsonPath("$.warehouse.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.warehouse.snapshotId").isNotEmpty())
                .andExpect(jsonPath("$.results[0].similarityScore").isNumber());
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void returnsTypedDiagnosticsForSyntaxInjectionSemanticAndAlgorithmFailures() throws Exception {
        mvc.perform(post("/api/v1/research-queries/execute").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FIND THESIS; DROP TABLE studies\",\"includeTrace\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].stage").value("LEXER"));

        mvc.perform(post("/api/v1/research-queries/execute").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FIND RELATED WHERE SIMILARITY > 70\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("SEM_RELATED_CONTEXT_REQUIRED"));

        mvc.perform(post("/api/v1/research-queries/execute").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FIND THESIS WHERE RESEARCH_AREA = \\\"invented-area\\\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("SEM_UNKNOWN_RESEARCH_AREA"));

        mvc.perform(post("/api/v1/research-queries/execute").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FIND RELATED TO TEXT \\\"flood warning\\\" USING SEMANTIC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.assessmentStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("EXEC_SEMANTIC_UNAVAILABLE"));
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void omitsUnauthorizedRestrictedResultsAndNeverAuditsRawQueryText() throws Exception {
        String marker = "private-marker-" + UUID.randomUUID();
        String body = "{\"source\":\"FIND THESIS WHERE TOPIC != \\\"" + marker
                + "\\\" USING LEXICAL LIMIT 100\"}";
        String response = mvc.perform(post("/api/v1/research-queries/execute").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("\"restricted\":true", "Restricted catalogue record")
                .doesNotContain("Clinical units need controlled access to patient information")
                .doesNotContain("patient records, radiology reports");
        assertThat(audit.list(500).stream().filter(event -> event.action().equals("RESEARCH_QUERY_EXECUTED"))
                .map(JdbcAuditService.AuditView::snapshotJson)).noneMatch(snapshot -> snapshot.contains(marker));
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void deniesCrossDepartmentProposalContextWithoutRevealingWhetherItExists() throws Exception {
        UUID departmentId = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        byte[] actor = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='admin@ugnay.local'", byte[].class);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO departments(id,code,name,active,row_version,created_at) VALUES(?,?,?,?,?,?)",
                bytes(departmentId), "X" + departmentId.toString().substring(0, 6), "Other Department", true, 0,
                Timestamp.from(now));
        jdbc.update("INSERT INTO problem_cases(id,department_id,created_by,title,problem_statement,stakeholder,affected_users,site_context,desired_outcome,constraints_text,privacy_classification,intake_status,row_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(problemId), bytes(departmentId), actor, "Protected proposal context",
                "A protected problem statement in another department.", "Office", "Researchers", "Other campus",
                "Protected outcome", null, "RESTRICTED", "SUBMITTED", 0, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO proposals(id,problem_case_id,submitted_by,proposed_title,proposed_solution,methodology,technology_text,data_sources_text,intended_users_text,proposal_status,row_version,submitted_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(proposalId), bytes(problemId), actor, "Protected proposal", "Protected solution", "Design science",
                "Java", "Restricted records", "Researchers", "SUBMITTED", 0, Timestamp.from(now), Timestamp.from(now));

        String body = "{\"source\":\"FIND RELATED TO PROPOSAL \\\"" + proposalId
                + "\\\" USING LEXICAL\"}";
        mvc.perform(post("/api/v1/research-queries/execute").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("SEM_PROPOSAL_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "The proposal context is unavailable, ambiguous, or not authorized."));
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "STUDENT")
    void excludesCrossDepartmentInternalStudiesBeforeRankingAndCounting() throws Exception {
        UUID departmentId = UUID.randomUUID();
        UUID studyId = UUID.randomUUID();
        String marker = "cross-department-internal-" + studyId;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO departments(id,code,name,active,row_version,created_at) VALUES(?,?,?,?,?,?)",
                bytes(departmentId), "I" + studyId.toString().substring(0, 6), "Internal Research Office", true, 0,
                Timestamp.from(now));
        jdbc.update("INSERT INTO studies(id,department_id,institutional_code,title,academic_year,lifecycle_status,visibility,row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(studyId), bytes(departmentId), "INT-" + studyId.toString().substring(0, 8), marker,
                "2025-2026", "COMPLETED", "INTERNAL", 0, Timestamp.from(now));

        mvc.perform(post("/api/v1/warehouse/refresh").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("admin@ugnay.local").roles("CURATOR")))
                .andExpect(status().is2xxSuccessful());

        String body = "{\"source\":\"FIND THESIS WHERE TOPIC CONTAINS \\\"" + marker
                + "\\\" USING LEXICAL LIMIT 100\"}";
        String response = mvc.perform(post("/api/v1/research-queries/execute").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.results").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(marker, studyId.toString());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

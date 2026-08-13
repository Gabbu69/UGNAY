package com.ugnay.platform.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ugnay.platform.shared.JdbcAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-intake-decision;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntakeDecisionWorkflowTest {
    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcAuditService audit;

    @Test
    void intakeSubmissionIsAtomicIdempotentAndUsesTheAuthenticatedActor() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = intake("Evidence-linked campus queue continuity " + key.substring(0, 8));

        MvcResult created = mvc.perform(post("/api/v1/intakes").with(csrf())
                        .with(user("admin@ugnay.local").roles("STUDENT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.problem.id").isNotEmpty())
                .andExpect(jsonPath("$.proposal.id").isNotEmpty())
                .andExpect(jsonPath("$.discovery.id").isNotEmpty())
                .andExpect(jsonPath("$.evidenceReferences[0].verificationState").value("UNVERIFIED"))
                .andReturn();
        JsonNode first = json.readTree(created.getResponse().getContentAsString());
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("No constraints recorded")
                .doesNotContain("Solution approach intentionally deferred")
                .doesNotContain("Not yet assessed");
        int submissions = jdbc.queryForObject("SELECT COUNT(*) FROM intake_submissions WHERE idempotency_key=?", Integer.class, key);
        int evidence = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_references WHERE subject_type='PROBLEM_CASE' AND subject_id=(SELECT problem_case_id FROM intake_submissions WHERE idempotency_key=?)",
                Integer.class, key);

        MvcResult replay = mvc.perform(post("/api/v1/intakes").with(csrf())
                        .with(user("admin@ugnay.local").roles("STUDENT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn();
        JsonNode second = json.readTree(replay.getResponse().getContentAsString());
        assertThat(second.at("/problem/id").asText()).isEqualTo(first.at("/problem/id").asText());
        assertThat(second.at("/proposal/id").asText()).isEqualTo(first.at("/proposal/id").asText());
        assertThat(second.at("/discovery/id").asText()).isEqualTo(first.at("/discovery/id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM intake_submissions WHERE idempotency_key=?", Integer.class, key))
                .isEqualTo(submissions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_references WHERE subject_type='PROBLEM_CASE' AND subject_id=(SELECT problem_case_id FROM intake_submissions WHERE idempotency_key=?)",
                Integer.class, key)).isEqualTo(evidence);

        mvc.perform(post("/api/v1/intakes").with(csrf()).with(user("admin@ugnay.local").roles("STUDENT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("campus queue", "campus records")))
                .andExpect(status().isConflict());
        assertThat(audit.list(500)).anySatisfy(event -> {
            if (event.action().equals("INTAKE_SUBMITTED") && event.subjectId().toString().equals(first.at("/proposal/id").asText())) {
                assertThat(event.actorEmail()).isEqualTo("admin@ugnay.local");
            }
        });
    }

    @Test
    void intakeAcceptsAndPersistsDatasetEvidenceReferences() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = intake("Dataset-backed continuity " + key.substring(0, 8))
                .replace("\"type\": \"URL\"", "\"type\": \"DATASET\"")
                .replace("Research-office process note", "Research continuity dataset")
                .replace("https://example.edu/research-process", "https://example.edu/datasets/research-continuity");

        mvc.perform(post("/api/v1/intakes").with(csrf())
                        .with(user("admin@ugnay.local").roles("STUDENT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceReferences[0].type").value("DATASET"))
                .andExpect(jsonPath("$.evidenceReferences[0].label").value("Research continuity dataset"))
                .andExpect(jsonPath("$.evidenceReferences[0].location").value("https://example.edu/datasets/research-continuity"));

        assertThat(jdbc.queryForObject("""
                        SELECT reference_type FROM evidence_references
                        WHERE subject_type='PROBLEM_CASE'
                          AND subject_id=(SELECT problem_case_id FROM intake_submissions WHERE idempotency_key=?)
                        """, String.class, key))
                .isEqualTo("DATASET");
    }

    @Test
    void decisionContextIsExactAndCandidateBound() throws Exception {
        JsonNode first = submit(UUID.randomUUID().toString(), "Exact frozen route alpha " + UUID.randomUUID().toString().substring(0, 6));
        JsonNode second = submit(UUID.randomUUID().toString(), "Exact frozen route beta " + UUID.randomUUID().toString().substring(0, 6));
        String proposalId = first.at("/proposal/id").asText();
        String runId = first.at("/discovery/id").asText();
        String otherRunId = second.at("/discovery/id").asText();
        String outsiderEmail = "same.department.outsider@ugnay.local";
        byte[] departmentId = jdbc.queryForObject("SELECT pc.department_id FROM proposals p JOIN problem_cases pc ON pc.id=p.problem_case_id WHERE p.id=?",
                byte[].class, uuidBytes(UUID.fromString(proposalId)));
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status) VALUES(?,?,?,?,?)",
                uuidBytes(UUID.randomUUID()), departmentId, outsiderEmail, "Same Department Outsider", "ACTIVE");

        mvc.perform(get("/api/v1/proposals/{proposalId}/decision-context/{runId}", proposalId, runId)
                        .with(user("admin@ugnay.local").roles("COORDINATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.id").value(proposalId))
                .andExpect(jsonPath("$.discovery.id").value(runId))
                .andExpect(jsonPath("$.adviserRecommendations").isArray());
        mvc.perform(get("/api/v1/proposals/{proposalId}/decision-context/{runId}", proposalId, otherRunId)
                        .with(user("admin@ugnay.local").roles("COORDINATOR")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/proposals/{proposalId}", proposalId)
                        .with(user(outsiderEmail).roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects").param("mine", "false").with(user(outsiderEmail).roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/change-requests").with(user(outsiderEmail).roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        String missingCandidate = """
                {"proposalId":"%s","discoveryRunId":"%s","disposition":"CLOSE_AS_DUPLICATE",
                 "rationale":"The coordinator reviewed the frozen evidence and is recording a duplicate closure."}
                """.formatted(proposalId, runId);
        mvc.perform(post("/api/v1/proposal-decisions").with(csrf())
                        .with(user("admin@ugnay.local").roles("COORDINATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(missingCandidate))
                .andExpect(status().isUnprocessableEntity());
    }

    private JsonNode submit(String key, String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/intakes").with(csrf())
                        .with(user("admin@ugnay.local").roles("STUDENT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(intake(title)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String intake(String title) {
        return """
                {
                  "problem": {
                    "title": "%s",
                    "problemStatement": "Campus research evidence is collected in disconnected records, so reviewers cannot reliably trace the problem into a preserved successor decision.",
                    "stakeholder": "University research office",
                    "affectedUsers": "Student researchers and academic reviewers",
                    "siteContext": "College capstone review process",
                    "desiredOutcome": "Preserve one reviewable problem-to-successor evidence chain",
                    "constraints": null,
                    "privacyClassification": "INTERNAL"
                  },
                  "proposal": {
                    "title": "%s",
                    "objectives": ["Trace reviewed evidence into an accountable academic decision"],
                    "proposedSolution": "A bounded research-continuity workflow with human academic routing",
                    "methodology": null,
                    "dataSources": null,
                    "technology": null,
                    "intendedUsers": "Student researchers and academic reviewers"
                  },
                  "evidenceReferences": [{
                    "type": "URL",
                    "label": "Research-office process note",
                    "location": "https://example.edu/research-process",
                    "storedDocumentId": null,
                    "sha256": null
                  }]
                }
                """.formatted(title, title);
    }

    private static byte[] uuidBytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.Study;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-routing-evidence-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutingEvidenceRepositoryTest {
    @Autowired RoutingEvidenceRepository repository;
    @Autowired WorkspaceService workspace;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void missingRouteEvidenceIsUnassessedAndCarriesNoFalseZeros() throws Exception {
        Proposal proposal = createProposal("Unassessed continuation route", List.of(
                "Preserve predecessor evidence without claiming unavailable measurements"));
        Study predecessor = incompletePredecessor();

        RoutingEvidenceRepository.RouteAssessment assessment = repository.assessment(proposal.id(), predecessor.id());

        assertThat(assessment.continuationState()).isEqualTo(AssessmentStatus.UNASSESSED);
        assertThat(assessment.continuationCoverage()).isNull();
        assertThat(assessment.codeAccess()).isNull();
        assertThat(assessment.dataAccess()).isNull();
        assertThat(assessment.improvementState()).isEqualTo(AssessmentStatus.UNASSESSED);
        assertThat(assessment.improvementClaimCount()).isNull();
        assertThat(assessment.continuationReady()).isFalse();
        assertThat(assessment.improvementReady()).isFalse();

        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", proposal.id(), predecessor.id())
                .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continuationState").value("UNASSESSED"))
                .andExpect(jsonPath("$.continuationCoverage").doesNotExist())
                .andExpect(jsonPath("$.codeAccess").doesNotExist())
                .andExpect(jsonPath("$.dataAccess").doesNotExist())
                .andExpect(jsonPath("$.improvementState").value("UNASSESSED"))
                .andExpect(jsonPath("$.improvementClaimCount").doesNotExist())
                .andExpect(jsonPath("$.continuationReady").doesNotExist())
                .andExpect(jsonPath("$.improvementReady").doesNotExist());
    }

    @Test
    void savingTwiceAppendsRevisionsAndKeepsPriorMappings() {
        Proposal proposal = createProposal("Append-only continuation route", List.of(
                "Continue the predecessor authorization workflow",
                "Verify the inherited evidence boundary"));
        Study predecessor = incompletePredecessor();
        UUID objectiveId = jdbc.queryForObject(
                "SELECT id FROM proposal_objectives WHERE proposal_id=? ORDER BY objective_order LIMIT 1",
                (result, index) -> uuid(result.getBytes(1)), bytes(proposal.id()));
        UUID continuationItemId = predecessor.continuationItems().stream()
                .filter(item -> "OPEN".equals(item.status())).findFirst().orElseThrow().id();

        repository.saveContinuation(proposal.id(), predecessor.id(),
                List.of(new RoutingEvidenceRepository.ObjectiveLink(objectiveId, continuationItemId,
                        "Revision one maps the inherited authorization work.")),
                false, false, "Repository and data access still require confirmation.", "admin@ugnay.local");
        RoutingEvidenceRepository.ContinuationAssessment latest = repository.saveContinuation(
                proposal.id(), predecessor.id(),
                List.of(new RoutingEvidenceRepository.ObjectiveLink(objectiveId, continuationItemId,
                        "Revision two confirms repository access while retaining the earlier record.")),
                true, false, "Repository access is confirmed; data access remains pending.", "admin@ugnay.local");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM proposal_continuation_revisions WHERE proposal_id=? AND predecessor_study_id=?",
                Integer.class, bytes(proposal.id()), bytes(predecessor.id()))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM proposal_continuation_revision_links links JOIN proposal_continuation_revisions revisions ON revisions.id=links.revision_id WHERE revisions.proposal_id=?",
                Integer.class, bytes(proposal.id()))).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT access_notes FROM proposal_continuation_revisions WHERE proposal_id=? ORDER BY revision_number",
                String.class, bytes(proposal.id()))).containsExactly(
                        "Repository and data access still require confirmation.",
                        "Repository access is confirmed; data access remains pending.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_continuation_evidence WHERE proposal_id=?",
                Integer.class, bytes(proposal.id()))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_objective_continuation_links WHERE proposal_id=?",
                Integer.class, bytes(proposal.id()))).isZero();

        assertThat(latest.state()).isEqualTo(AssessmentStatus.ASSESSED);
        assertThat(latest.revisionNumber()).isEqualTo(2);
        assertThat(latest.objectiveCoverage()).isEqualTo(50.0);
        assertThat(latest.codeAccessConfirmed()).isTrue();
        assertThat(latest.dataAccessConfirmed()).isFalse();

        RoutingEvidenceRepository.RouteAssessment route = repository.assessment(proposal.id(), predecessor.id());
        assertThat(route.continuationState()).isEqualTo(AssessmentStatus.ASSESSED);
        assertThat(route.continuationCoverage()).isEqualTo(50.0);
        assertThat(route.codeAccess()).isTrue();
        assertThat(route.dataAccess()).isFalse();
        assertThat(route.improvementState()).isEqualTo(AssessmentStatus.UNASSESSED);
        assertThat(route.improvementClaimCount()).isNull();
    }

    private Proposal createProposal(String title, List<String> objectives) {
        var problem = workspace.createProblem(title + " problem",
                "Predecessor work cannot be continued safely because prerequisite evidence is not revisioned.",
                "Research coordinator", "Student researchers", "CICS",
                "Preserve every submitted continuation prerequisite revision.",
                "Use authorized institutional evidence only.", "INTERNAL", 1, "admin@ugnay.local");
        return workspace.createProposal(problem.id(), title, objectives,
                "Record append-only continuation prerequisites.", "Design science",
                "Existing project records", "Java and MySQL", "Student researchers", "admin@ugnay.local");
    }

    private Study incompletePredecessor() {
        return workspace.studies().stream()
                .filter(study -> "INCOMPLETE".equals(study.lifecycleStatus()) || "SUSPENDED".equals(study.lifecycleStatus()))
                .filter(study -> study.continuationItems().stream().anyMatch(item -> "OPEN".equals(item.status())))
                .findFirst().orElseThrow();
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

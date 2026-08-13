package com.ugnay.platform.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-completion-evidence-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class CompletionEvidenceWorkflowTest {
    @Autowired WorkspaceService workspace;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void derivesReadinessAndRequiresIndependentReferenceVerification() {
        var project = workspace.projects().stream().filter(value -> !"COMPLETED".equals(value.status().name())).findFirst().orElseThrow();
        var submitted = workspace.updateCompletionEvidence(project.id(), "https://example.edu/continuity", "abc123def456",
                "Run the pinned release and follow the documented setup instructions.", List.of("Pilot scope only"),
                List.of("Validate a second department"), List.of("Complete longitudinal evaluation"),
                List.of(
                        new WorkspaceService.EvidenceReferenceInput("REPOSITORY", "Repository and licence handoff",
                                "https://example.edu/continuity", null, null),
                        new WorkspaceService.EvidenceReferenceInput("OUTPUT", "Versioned final output",
                                "https://example.edu/continuity/output", null, null),
                        new WorkspaceService.EvidenceReferenceInput("TEST_RUN", "Release test run",
                                "https://example.edu/continuity/tests", null, null),
                        new WorkspaceService.EvidenceReferenceInput("DOCUMENT", "Data custodian rights approval",
                                "https://example.edu/continuity/rights", null, null)),
                "admin@ugnay.local");

        assertThat(submitted.artifact().criteria()).allSatisfy(criterion ->
                assertThat(criterion.state()).isIn(AssessmentStatus.ASSESSED, AssessmentStatus.PARTIAL, AssessmentStatus.UNASSESSED));
        assertThat(submitted.artifact().criteria()).filteredOn(value -> value.key().equals("rights")).singleElement()
                .satisfies(value -> {
                    assertThat(value.state()).isEqualTo(AssessmentStatus.PARTIAL);
                    assertThat(value.value()).isNull();
                });
        assertThat(submitted.artifact().readinessState()).isEqualTo(AssessmentStatus.PARTIAL);
        assertThat(submitted.artifact().readinessScore()).isNull();

        var rightsReference = workspace.completionEvidenceReferences(project.id()).stream()
                .filter(value -> value.label().contains("rights")).findFirst().orElseThrow();
        assertThatThrownBy(() -> workspace.verifyCompletionReference(project.id(), rightsReference.id(), "VERIFIED",
                "The recorder must not verify their own submitted rights evidence.", "admin@ugnay.local"))
                .isInstanceOf(EvidenceVerificationException.class).hasMessageContaining("different authenticated project member");

        UUID reviewer = UUID.randomUUID();
        byte[] department = jdbc.queryForObject("SELECT department_id FROM projects WHERE id=?", byte[].class, bytes(project.id()));
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status,row_version,created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(reviewer), department, "completion.reviewer@ugnay.local", "Completion Evidence Reviewer", "ACTIVE", 0,
                Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO project_memberships(project_id,user_id,membership_role,joined_at) VALUES(?,?,?,?)",
                bytes(project.id()), bytes(reviewer), "REVIEWER", Timestamp.from(Instant.now()));

        var verified = workspace.verifyCompletionReference(project.id(), rightsReference.id(), "VERIFIED",
                "The referenced custodian approval identifies the project, repository, and permitted data access.",
                "completion.reviewer@ugnay.local");
        assertThat(verified.artifact().codeDataRightsConfirmed()).isTrue();
        assertThat(verified.artifact().criteria()).filteredOn(value -> value.key().equals("rights")).singleElement()
                .satisfies(value -> {
                    assertThat(value.state()).isEqualTo(AssessmentStatus.ASSESSED);
                    assertThat(value.value()).isEqualTo(1.0);
                    assertThat(value.source()).isEqualTo("VERIFIED_RIGHTS_REFERENCE");
                    assertThat(value.assessedAt()).isNotNull();
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_reference_verifications WHERE evidence_reference_id=?",
                Integer.class, bytes(rightsReference.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verification_state FROM evidence_references WHERE id=?", String.class,
                bytes(rightsReference.id()))).isEqualTo("VERIFIED");
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.CandidateEvidence;
import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.CompletionPackage;
import com.ugnay.platform.shared.PlatformModels.ComponentScore;
import com.ugnay.platform.shared.PlatformModels.ContinuityCriterion;
import com.ugnay.platform.shared.PlatformModels.ContinuationItem;
import com.ugnay.platform.shared.PlatformModels.Coverage;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.HealthDimension;
import com.ugnay.platform.shared.PlatformModels.ImpactPreview;
import com.ugnay.platform.shared.PlatformModels.ImpactedArtifact;
import com.ugnay.platform.shared.PlatformModels.Lineage;
import com.ugnay.platform.shared.PlatformModels.LineageEdge;
import com.ugnay.platform.shared.PlatformModels.LineageNode;
import com.ugnay.platform.shared.PlatformModels.LineageType;
import com.ugnay.platform.shared.PlatformModels.ProblemCase;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectHealth;
import com.ugnay.platform.shared.PlatformModels.ProjectStatus;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.ProposalDecision;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.ReviewQueueItem;
import com.ugnay.platform.shared.PlatformModels.ScopeRisk;
import com.ugnay.platform.shared.PlatformModels.Severity;
import com.ugnay.platform.shared.PlatformModels.Study;
import com.ugnay.platform.shared.PlatformModels.TestExecution;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import com.ugnay.platform.shared.PlatformModels.Traceability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-store-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@ActiveProfiles("test")
class JdbcWorkspaceStoreTest {
    @Autowired JdbcWorkspaceStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void normalizedEvidenceChainSurvivesFreshStoreInstance() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        UUID studyId = UUID.randomUUID();
        UUID continuationId = UUID.randomUUID();
        Study study = new Study(studyId, "TEST-" + studyId, "Flood Evidence Predecessor", "2025-2026",
                "College of Information and Computing Sciences", "COMPLETED", "CAMPUS", "Prior evidence", "Flood gaps",
                List.of("Preserve readiness evidence"), List.of("baha", "flood"), "Design science", "Offline plan",
                "Assessments", "Java", "Coordinators", "Campus DRRM", "Main campus",
                List.of(new ContinuationItem(continuationId, studyId, "RECOMMENDATION", "Validate routes", "Map safe routes", "OPEN", false)));
        store.saveStudy(study);

        UUID problemId = UUID.randomUUID();
        ProblemCase problem = new ProblemCase(problemId, "Campus flood evidence gap", "Plans are disconnected from evidence.",
                "Campus DRRM", "Students", "Main campus", "Preserve an offline plan", "Internal data only", "INTERNAL",
                "READY", 2, now, 0);
        store.saveProblem(problem);

        UUID proposalId = UUID.randomUUID();
        Proposal proposal = new Proposal(proposalId, "Evidence-linked flood continuity", problem.problemStatement(),
                problem.stakeholder(), problem.affectedUsers(), problem.siteContext(), problem.desiredOutcome(), problem.constraints(),
                problem.privacyClassification(), List.of("Assess readiness", "Preserve offline plans"), "Improve the predecessor",
                "Scenario validation", "Building data", "Java and MySQL", "Coordinators", "SUBMITTED", now, 0);
        store.saveProposal(proposal, problemId);

        UUID discoveryId = UUID.randomUUID();
        DiscoveryCandidate candidate = new DiscoveryCandidate(1, studyId, study.title(), 82, 77, 74, 91, "VERY_STRONG", false,
                List.of(new CandidateEvidence("problem", "evidence gap", "flood gaps",
                        List.of(new ComponentScore("semantic", 84, .5, 42, "Related meaning", List.of("flood", "baha"))))));
        DiscoveryRun discovery = new DiscoveryRun(discoveryId, proposalId, AssessmentStatus.ASSESSED, Recommendation.IMPROVE,
                91, "hybrid-test-v1", "a".repeat(64), "local-e5", "Completed predecessor has a measurable limitation.",
                List.of("Confirm data access"), List.of(candidate), now);
        store.saveDiscovery(discovery);
        store.saveDecision(new ProposalDecision(UUID.randomUUID(), proposalId, discoveryId, DecisionDisposition.APPROVE_IMPROVE,
                "Improvement evidence accepted.", "Pilot Coordinator", now, studyId));

        UUID projectId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        Project project = new Project(projectId, "UGNAY-TEST-" + projectId.toString().substring(0, 8), proposal.title(),
                ProjectStatus.ACTIVE, Recommendation.IMPROVE, study.department(), baselineId, 1,
                List.of("Researcher One", "Adviser One"), now, 0);
        store.saveProject(project, proposalId);

        TraceItem requirement = new TraceItem(UUID.randomUUID(), "R-01", TraceItemType.REQUIREMENT, "Offline evidence",
                "The system preserves evidence offline.", "APPROVED", "MUST", "Opens within two seconds", "TEST", 1, 95);
        TraceItem test = new TraceItem(UUID.randomUUID(), "T-01", TraceItemType.TEST_CASE, "Offline evidence test",
                "Verify offline access.", "APPROVED", "MANDATORY", null, null, 1, 100);
        TraceLink link = new TraceLink(UUID.randomUUID(), requirement.id(), test.id(), "VERIFIED_BY", "ACTIVE", "Mandatory evidence path");
        TestExecution execution = new TestExecution(UUID.randomUUID(), test.id(), "PASSED", "build-1", true, true, now);
        Finding finding = new Finding(UUID.randomUUID(), "TRACE_EXAMPLE", Severity.INFO, FindingState.RESOLVED, "Trace checked",
                "The evidence path is complete.", "No action", List.of(requirement.id()), "alignment-v1");
        Coverage coverage = new Coverage(AssessmentStatus.ASSESSED, 100, 100, 100, 100, 1, 1);
        Traceability trace = new Traceability(projectId, baselineId, 1, AssessmentStatus.ASSESSED,
                List.of(requirement, test), List.of(link), List.of(execution), List.of(finding), coverage);
        store.saveTraceability(trace);

        ScopeRisk risk = new ScopeRisk(AssessmentStatus.ASSESSED, 15, "LOW", 0, 5, 10, 0, List.of("Controlled growth only."));
        store.saveScopeRisk(projectId, baselineId, risk, now);
        ChangeRequest change = new ChangeRequest(UUID.randomUUID(), projectId, baselineId, "Clarify offline threshold", "Remove ambiguity",
                "IMPACT_REVIEW", List.of(requirement.id()), List.of("NEW_SITE"), now, 0);
        store.saveChange(change);
        ImpactedArtifact impacted = new ImpactedArtifact(test.id(), test.key(), test.type(), test.title(), 1,
                List.of(requirement.id(), test.id()), Severity.HIGH, true, "Requirement revision invalidates evidence.");
        store.saveImpact(new ImpactPreview(change.id(), baselineId, true, risk, List.of(impacted), List.of("Test protocol"), now));

        CompletionPackage completion = new CompletionPackage(UUID.randomUUID(), projectId, "READY", 100, true,
                List.of(new ContinuityCriterion("trace", "Trace history", 20, 1, "Baseline preserved")), List.of(),
                "https://example.edu/repository", "abc123", "Run with Docker Compose", List.of("Pilot site only"),
                List.of("Validate another site"), List.of("Second-semester evaluation"));
        store.saveCompletion(completion);
        Lineage lineage = new Lineage(projectId,
                List.of(new LineageNode(studyId, "STUDY", study.title(), "COMPLETED", study.academicYear(), false),
                        new LineageNode(projectId, "PROJECT", project.title(), "ACTIVE", "2026-2027", true)),
                List.of(new LineageEdge(UUID.randomUUID(), studyId, projectId, LineageType.IMPROVES, true, "Builds on open recommendation")));
        store.saveLineage(lineage);
        ProjectHealth health = new ProjectHealth(projectId, "HEALTHY",
                List.of(new HealthDimension("alignment", "Alignment", AssessmentStatus.ASSESSED, 100.0, "HEALTHY", "All links present")),
                0, 0, now, "health-v1");
        store.saveHealth(health);
        ReviewQueueItem review = new ReviewQueueItem(UUID.randomUUID(), "CHANGE_IMPACT", change.title(), project.code(), Severity.HIGH,
                "COORDINATOR", "Review boundary expansion", now.plusSeconds(86400));
        store.replaceReviewQueue(List.of(review));

        JdbcWorkspaceStore restarted = new JdbcWorkspaceStore(jdbc);
        JdbcWorkspaceStore.WorkspaceState loaded = restarted.load();

        assertThat(loaded.studies()).anySatisfy(saved -> {
            assertThat(saved.id()).isEqualTo(studyId);
            assertThat(saved.objectives()).containsExactly("Preserve readiness evidence");
            assertThat(saved.keywords()).containsExactlyInAnyOrder("baha", "flood");
            assertThat(saved.continuationItems()).extracting(ContinuationItem::id).contains(continuationId);
        });
        assertThat(loaded.proposalProblemIds()).containsEntry(proposalId, problemId);
        assertThat(loaded.discoveryRuns()).filteredOn(run -> run.id().equals(discoveryId)).singleElement()
                .satisfies(run -> assertThat(run.candidates().getFirst().evidence().getFirst().components()).hasSize(1));
        assertThat(loaded.projectProposalIds()).containsEntry(projectId, proposalId);
        assertThat(loaded.traceability()).filteredOn(saved -> saved.projectId().equals(projectId)).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.items()).hasSize(2);
                    assertThat(saved.coverage().priorityWeightedPassingCoverage()).isEqualTo(100);
                    assertThat(saved.executions().getFirst().hasEvidence()).isTrue();
                });
        assertThat(loaded.changeRequests()).extracting(ChangeRequest::id).contains(change.id());
        assertThat(loaded.impactPreviews()).containsKey(change.id());
        assertThat(loaded.completionPackages()).extracting(CompletionPackage::id).contains(completion.id());
        assertThat(loaded.lineages()).containsKey(projectId);
        assertThat(loaded.health()).containsKey(projectId);
        assertThat(loaded.reviewQueue()).extracting(ReviewQueueItem::id).contains(review.id());
    }
}

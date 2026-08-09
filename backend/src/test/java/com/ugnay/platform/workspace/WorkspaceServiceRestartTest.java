package com.ugnay.platform.workspace;

import com.ugnay.platform.analytics.AlignmentAnalyzer;
import com.ugnay.platform.changecontrol.ChangeImpactAnalyzer;
import com.ugnay.platform.continuity.LineageValidator;
import com.ugnay.platform.discovery.SimilarityEngine;
import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.CompletionPackage;
import com.ugnay.platform.shared.PlatformModels.ContinuityCriterion;
import com.ugnay.platform.shared.PlatformModels.Coverage;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.ProblemCase;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.Proposal;
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

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-app-restart-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@ActiveProfiles("test")
class WorkspaceServiceRestartTest {
    @Autowired WorkspaceService service;
    @Autowired SimilarityEngine similarity;
    @Autowired AlignmentAnalyzer alignment;
    @Autowired ChangeImpactAnalyzer impact;
    @Autowired LineageValidator lineage;
    @Autowired JdbcAuditService audit;
    @Autowired JdbcWorkspaceStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void approvedDecisionCreatesRestartSafeProjectBaselineAndContinuityPackage() {
        ProblemCase problem = service.createProblem("Restart-safe campus concern",
                "Campus coordinators cannot preserve the evidence behind safety decisions when a student project ends.",
                "Campus DRRM Office", "Students and coordinators", "Main campus", "Preserve evidence for successors",
                "Use non-sensitive records only", "INTERNAL", 1);
        Proposal proposal = service.createProposal(problem.id(), "Restart-safe continuity pilot",
                List.of("Preserve approved evidence", "Create a reusable successor baseline"),
                "Build a continuity evidence chain", "Design science", "Approved campus evidence", "Java and MySQL",
                "Student researchers and coordinators");
        var discovery = service.runDiscovery(proposal.id());
        int projectsBefore = service.projects().size();
        service.decide(proposal.id(), discovery.id(), DecisionDisposition.APPROVE_NEW,
                "The documented gap and distinct evidence-preservation outcome justify a new pilot study.", null);

        Project created = service.projects().stream().filter(candidate -> candidate.title().equals(proposal.title())).findFirst().orElseThrow();
        assertThat(service.projects()).hasSize(projectsBefore + 1);
        assertThat(service.traceability(created.id()).baselineNumber()).isEqualTo(1);
        assertThat(service.completionPackage(created.id()).status()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id=?", Integer.class, uuidBytes(created.id()))).isEqualTo(1);

        WorkspaceService restarted = new WorkspaceService(similarity, alignment, impact, lineage, "hybrid-v1.0.0", audit,
                new JdbcWorkspaceStore(jdbc));
        restarted.initializePersistence();

        assertThat(restarted.problem(problem.id())).satisfies(reloaded -> {
            assertThat(reloaded.title()).isEqualTo(problem.title());
            assertThat(reloaded.problemStatement()).isEqualTo(problem.problemStatement());
            assertThat(reloaded.evidenceCount()).isEqualTo(problem.evidenceCount());
        });
        assertThat(restarted.proposal(proposal.id()).status()).isEqualTo("APPROVED_NEW");
        assertThat(restarted.project(created.id()).currentBaselineId()).isEqualTo(created.currentBaselineId());
        assertThat(restarted.traceability(created.id()).items()).extracting(item -> item.type().name())
                .contains("PROBLEM", "OBJECTIVE");
        assertThat(restarted.completionPackage(created.id()).criteria()).hasSize(6);
    }

    @Test
    void eligibleCompletionPublishesExactlyOneRestartSafeCatalogueStudy() {
        ProblemCase problem = service.createProblem("Completion lineage concern",
                "A completed campus software study can disappear without verified outputs and a structured handoff for successors.",
                "Research Coordinator", "Future student researchers", "CICS", "Publish a reusable continuity record",
                "Use campus-authenticated evidence", "INTERNAL", 1);
        Proposal proposal = service.createProposal(problem.id(), "Completion lineage pilot",
                List.of("Preserve the final output for successor discovery"), "Create a verified continuity package",
                "Scenario validation", "Project evidence", "Java and MySQL", "Student researchers");
        var discovery = service.runDiscovery(proposal.id());
        service.decide(proposal.id(), discovery.id(), DecisionDisposition.APPROVE_NEW,
                "The distinct continuity outcome and preserved evidence justify a new project route for this pilot.", null);
        Project project = service.projects().stream().filter(candidate -> candidate.title().equals(proposal.title())).findFirst().orElseThrow();
        Traceability original = service.traceability(project.id());

        TraceItem requirement = new TraceItem(UUID.randomUUID(), "R-COMPLETE", TraceItemType.REQUIREMENT,
                "Preserve final output", "The system shall preserve the final output for successors.", "APPROVED", "MUST",
                "The final output is available from the continuity record.", "TEST", 1, 100);
        TraceItem feature = new TraceItem(UUID.randomUUID(), "F-COMPLETE", TraceItemType.FEATURE, "Continuity record",
                "Publishes verified final evidence.", "APPROVED", null, null, null, 1, 100);
        TraceItem test = new TraceItem(UUID.randomUUID(), "T-COMPLETE", TraceItemType.TEST_CASE, "Continuity output test",
                "Verify successor access to the final output.", "APPROVED", "MANDATORY", null, null, 1, 100);
        TraceItem output = new TraceItem(UUID.randomUUID(), "OUT-COMPLETE", TraceItemType.OUTPUT, "Reusable final output",
                "Versioned continuity evidence.", "APPROVED", null, null, null, 1, 100);
        List<TraceItem> items = new ArrayList<>(original.items());
        items.addAll(List.of(requirement, feature, test, output));
        List<TraceLink> links = new ArrayList<>(original.links());
        original.items().stream().filter(item -> item.type() == TraceItemType.OBJECTIVE).forEach(objective ->
                links.add(new TraceLink(UUID.randomUUID(), objective.id(), requirement.id(), "DECOMPOSES_TO", "ACTIVE", "Completion path")));
        links.add(new TraceLink(UUID.randomUUID(), requirement.id(), feature.id(), "REALIZED_BY", "ACTIVE", "Completion path"));
        links.add(new TraceLink(UUID.randomUUID(), requirement.id(), test.id(), "VERIFIED_BY", "ACTIVE", "Completion path"));
        links.add(new TraceLink(UUID.randomUUID(), feature.id(), output.id(), "CONTRIBUTES_TO", "ACTIVE", "Completion path"));
        TestExecution execution = new TestExecution(UUID.randomUUID(), test.id(), "PASSED", "release-complete", true, true,
                java.time.Instant.now());
        Traceability eligibleTrace = new Traceability(project.id(), project.currentBaselineId(), project.baselineNumber(),
                AssessmentStatus.ASSESSED, items, links, List.of(execution), List.of(),
                new Coverage(AssessmentStatus.ASSESSED, 100, 100, 100, 100, 1, 1));
        store.saveTraceability(eligibleTrace);
        CompletionPackage ready = new CompletionPackage(service.completionPackage(project.id()).id(), project.id(), "READY", 100, true,
                List.of(
                        new ContinuityCriterion("trace", "Trace", 20, 1, "Complete"),
                        new ContinuityCriterion("outputs", "Outputs", 15, 1, "Complete"),
                        new ContinuityCriterion("repository", "Repository", 20, 1, "Complete"),
                        new ContinuityCriterion("tests", "Tests", 15, 1, "Complete"),
                        new ContinuityCriterion("future-work", "Future work", 15, 1, "Complete"),
                        new ContinuityCriterion("rights", "Rights", 15, 1, "Complete")),
                List.of(), "https://example.edu/completion-pilot", "release123", "Run the documented Compose profile.",
                List.of("Pilot context only"), List.of("Validate in another department"), List.of("Longitudinal evaluation"));
        store.saveCompletion(ready);
        service.initializePersistence();

        var first = service.completionAssessment(project.id());
        var second = service.completionAssessment(project.id());

        assertThat(first.get("eligible")).isEqualTo(true);
        assertThat(second.get("eligible")).isEqualTo(true);
        assertThat(service.project(project.id()).status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studies WHERE source_project_id=?", Integer.class, uuidBytes(project.id())))
                .isEqualTo(1);
        WorkspaceService restarted = new WorkspaceService(similarity, alignment, impact, lineage, "hybrid-v1.0.0", audit,
                new JdbcWorkspaceStore(jdbc));
        restarted.initializePersistence();
        assertThat(restarted.project(project.id()).status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studies WHERE source_project_id=?", Integer.class, uuidBytes(project.id())))
                .isEqualTo(1);
    }

    private static byte[] uuidBytes(java.util.UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

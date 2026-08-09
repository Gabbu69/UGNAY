package com.ugnay.platform.workspace;

import com.ugnay.platform.analytics.AlignmentAnalyzer;
import com.ugnay.platform.changecontrol.ChangeImpactAnalyzer;
import com.ugnay.platform.continuity.LineageValidator;
import com.ugnay.platform.discovery.SimilarityEngine;
import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-authoring-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthoringWorkflowTest {
    @Autowired WorkspaceService service;
    @Autowired JdbcWorkspaceStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SimilarityEngine similarity;
    @Autowired AlignmentAnalyzer alignment;
    @Autowired ChangeImpactAnalyzer impact;
    @Autowired LineageValidator lineage;
    @Autowired JdbcAuditService audit;

    @Test
    void authoringBaselineEvidenceAndCompletionPackageSurviveRestart() {
        Project project = createApprovedProject("Normalized authoring continuity pilot");
        UUID originalBaseline = project.currentBaselineId();
        int originalBaselineItems = jdbc.queryForObject("SELECT COUNT(*) FROM baseline_items WHERE baseline_id=?", Integer.class,
                bytes(originalBaseline));
        TraceItem objective = service.traceability(project.id()).items().stream()
                .filter(item -> item.type() == TraceItemType.OBJECTIVE).findFirst().orElseThrow();
        Project otherProject = service.projects().stream().filter(value -> !value.id().equals(project.id())).findFirst().orElseThrow();
        TraceItem foreignItem = service.traceability(otherProject.id()).items().getFirst();
        assertThatThrownBy(() -> service.createTraceLink(project.id(), objective.id(), foreignItem.id(), "DECOMPOSES_TO",
                "Cross-project trace evidence must never be accepted.", "admin@ugnay.local"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong");

        TraceItem requirement = service.createTraceItem(project.id(), "R-AUTH", TraceItemType.REQUIREMENT,
                "Preserve successor evidence", "The system shall preserve project data and show an error to the user when continuity evidence is unavailable.",
                "MUST", "Given an approved output, a successor opens its evidence record within two seconds.", "TEST",
                "admin@ugnay.local").artifact();
        TraceItem feature = service.createTraceItem(project.id(), "F-AUTH", TraceItemType.FEATURE,
                "Continuity evidence viewer", "Displays versioned output and handoff evidence to an authorized successor.",
                null, null, null, "admin@ugnay.local").artifact();
        TraceItem test = service.createTraceItem(project.id(), "T-AUTH", TraceItemType.TEST_CASE,
                "Successor evidence test", "Verifies an authorized successor can open the preserved output evidence.",
                "MANDATORY", null, null, "admin@ugnay.local").artifact();
        TraceItem output = service.createTraceItem(project.id(), "OUT-AUTH", TraceItemType.OUTPUT,
                "Versioned continuity record", "Final evidence package that a successor can inspect and continue.",
                null, null, null, "admin@ugnay.local").artifact();

        service.createTraceLink(project.id(), objective.id(), requirement.id(), "DECOMPOSES_TO",
                "The requirement directly operationalizes the approved continuity objective.", "admin@ugnay.local");
        service.createTraceLink(project.id(), requirement.id(), feature.id(), "REALIZED_BY",
                "The evidence viewer realizes the preservation requirement.", "admin@ugnay.local");
        service.createTraceLink(project.id(), requirement.id(), test.id(), "VERIFIED_BY",
                "The mandatory test verifies the measurable requirement criteria.", "admin@ugnay.local");
        service.createTraceLink(project.id(), feature.id(), output.id(), "CONTRIBUTES_TO",
                "The feature produces the versioned successor-facing output.", "admin@ugnay.local");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM baseline_items WHERE baseline_id=?", Integer.class, bytes(originalBaseline)))
                .isEqualTo(originalBaselineItems);
        var approved = service.approveBaseline(project.id(),
                "The complete problem-to-output chain is ready to become immutable baseline two.", "admin@ugnay.local");
        assertThat(approved.project().baselineNumber()).isEqualTo(2);
        assertThat(approved.baseline().items()).allMatch(item -> item.lifecycleStatus().equals("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM baseline_items WHERE baseline_id=?", Integer.class,
                bytes(approved.project().currentBaselineId()))).isEqualTo(approved.baseline().items().size());

        var execution = service.recordTestExecution(project.id(), test.id(), "PASSED", "release-authoring-1", true,
                "admin@ugnay.local").artifact();
        assertThat(execution.current()).isTrue();
        service.reviseTraceItem(project.id(), requirement.id(), "Preserve successor evidence",
                "The system shall preserve project data and show a security error to the user when signed continuity evidence is unavailable.",
                "MUST", "Given an approved output, a successor opens signed evidence within one second.", "TEST", "admin@ugnay.local");
        assertThat(service.traceability(project.id()).executions()).filteredOn(value -> value.id().equals(execution.id()))
                .singleElement().satisfies(value -> assertThat(value.current()).isFalse());

        List<WorkspaceService.CriterionEvidence> criteria = service.completionPackage(project.id()).criteria().stream()
                .map(value -> new WorkspaceService.CriterionEvidence(value.key(), .75, "Evidence is recorded and awaiting final coordinator review."))
                .toList();
        service.updateCompletionEvidence(project.id(), true, "https://example.edu/authoring-pilot", "abc123def456",
                "Run the verified Docker Compose profile and follow the seeded setup guide.", List.of("Pilot scope only"),
                List.of("Validate with another department"), List.of("Complete longitudinal evaluation"), criteria,
                "admin@ugnay.local");

        WorkspaceService restarted = new WorkspaceService(similarity, alignment, impact, lineage, "hybrid-v1.0.0", audit,
                new JdbcWorkspaceStore(jdbc));
        restarted.initializePersistence();

        assertThat(restarted.traceability(project.id()).items()).filteredOn(item -> item.id().equals(requirement.id()))
                .singleElement().satisfies(item -> {
                    assertThat(item.currentRevision()).isEqualTo(2);
                    assertThat(item.lifecycleStatus()).isEqualTo("DRAFT");
                });
        assertThat(restarted.traceability(project.id()).executions()).filteredOn(value -> value.id().equals(execution.id()))
                .singleElement().satisfies(value -> assertThat(value.current()).isFalse());
        assertThat(restarted.completionPackage(project.id()).repositoryUrl()).isEqualTo("https://example.edu/authoring-pilot");
        assertThat(restarted.project(project.id()).rowVersion()).isGreaterThanOrEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action_code IN ('TRACE_ITEM_CREATED','TRACE_LINK_CREATED','TEST_EXECUTION_RECORDED','TRACE_ITEM_REVISED','PROJECT_BASELINE_APPROVED','COMPLETION_EVIDENCE_UPDATED')",
                Integer.class)).isGreaterThanOrEqualTo(12);
    }

    @Test
    void authoringEndpointsRequireRoleAndConcreteCurrentEtag() throws Exception {
        Project project = service.projects().stream().filter(value -> value.status().name() != "COMPLETED").findFirst().orElseThrow();
        String request = """
                {"key":"R-API-%s","type":"REQUIREMENT","title":"API evidence requirement",
                 "description":"The system shall preserve data for the authenticated user and report an error.",
                 "priority":"MUST","acceptanceCriteria":"Evidence opens in two seconds.","verificationMethod":"TEST"}
                """.formatted(UUID.randomUUID().toString().substring(0, 6).toUpperCase());

        mvc.perform(post("/api/v1/projects/{id}/trace-items", project.id()).with(user("student@ugnay.local").roles("STUDENT"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isPreconditionRequired());

        String originalEtag = "\"" + service.project(project.id()).rowVersion() + "\"";
        mvc.perform(post("/api/v1/projects/{id}/trace-items", project.id()).with(user("student@ugnay.local").roles("STUDENT"))
                        .with(csrf()).header("If-Match", originalEtag).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"" + (project.rowVersion() + 1) + "\""));

        String staleRequest = request.replace("R-API-", "R-OLD-");
        mvc.perform(post("/api/v1/projects/{id}/trace-items", project.id()).with(user("student@ugnay.local").roles("STUDENT"))
                        .with(csrf()).header("If-Match", originalEtag).contentType(MediaType.APPLICATION_JSON).content(staleRequest))
                .andExpect(status().isPreconditionFailed());

        String currentEtag = "\"" + service.project(project.id()).rowVersion() + "\"";
        mvc.perform(post("/api/v1/projects/{id}/baselines/approve", project.id())
                        .with(user("student@ugnay.local").roles("STUDENT")).with(csrf()).header("If-Match", currentEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rationale\":\"Student users cannot approve an immutable academic baseline.\"}"))
                .andExpect(status().isForbidden());
    }

    private Project createApprovedProject(String title) {
        var problem = service.createProblem(title + " problem",
                "Student project evidence is lost after completion because no normalized authoring and successor handoff chain exists.",
                "Research Coordinator", "Student researchers", "CICS", "Preserve an auditable successor chain",
                "Use internal records only", "INTERNAL", 1);
        Proposal proposal = service.createProposal(problem.id(), title, List.of("Preserve an auditable final output for successors"),
                "Build normalized continuity authoring", "Design science", "Approved project evidence", "Java and MySQL",
                "Student researchers");
        var discovery = service.runDiscovery(proposal.id());
        service.decide(proposal.id(), discovery.id(), DecisionDisposition.APPROVE_NEW,
                "The distinct normalized authoring and continuity outcome justifies this focused new pilot project.", null);
        return service.projects().stream().filter(project -> project.title().equals(title)).findFirst().orElseThrow();
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

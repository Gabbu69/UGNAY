package com.ugnay.platform.analytics;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectStatus;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.TestExecution;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlignmentAnalyzerTest {
    private final AlignmentAnalyzer analyzer = new AlignmentAnalyzer();

    @Test
    void noApprovedBaselineReturnsUnassessedScope() {
        Project project = project(null);
        var trace = analyzer.analyze(project, List.of(), List.of(), List.of());

        assertThat(analyzer.scopeRisk(project, trace, 0, 0, List.of()).status()).isEqualTo(AssessmentStatus.UNASSESSED);
        assertThat(analyzer.scopeRisk(project, trace, 0, 0, List.of()).score()).isNull();
    }

    @Test
    void untracedFeatureProducesExplainableFinding() {
        Project project = project(UUID.randomUUID());
        TraceItem feature = item("F-01", TraceItemType.FEATURE, "APPROVED", null, null, null);

        var trace = analyzer.analyze(project, List.of(feature), List.of(), List.of());

        assertThat(trace.findings()).anyMatch(finding -> finding.code().equals("UNJUSTIFIED_FEATURE")
                && finding.nextAction().contains("Link the feature"));
    }

    @Test
    void duplicateLinksDoNotInflateRequirementCoverageAndStaleEvidenceDoesNotPass() {
        Project project = project(UUID.randomUUID());
        TraceItem requirement = item("R-01", TraceItemType.REQUIREMENT, "APPROVED", "MUST", "Response is under two seconds.", "TEST");
        TraceItem feature = item("F-01", TraceItemType.FEATURE, "APPROVED", null, null, null);
        TraceItem test = item("T-01", TraceItemType.TEST_CASE, "APPROVED", "MANDATORY", null, null);
        List<TraceLink> links = List.of(
                link(requirement, feature), link(requirement, test), link(requirement, test));
        TestExecution stale = new TestExecution(UUID.randomUUID(), test.id(), "PASSED", "old-build", false, true, Instant.now());

        var trace = analyzer.analyze(project, List.of(requirement, feature, test), links, List.of(stale));

        assertThat(trace.coverage().totalRequirements()).isEqualTo(1);
        assertThat(trace.coverage().mappedCoverage()).isEqualTo(100);
        assertThat(trace.coverage().executedCoverage()).isZero();
        assertThat(trace.coverage().priorityWeightedPassingCoverage()).isZero();
        assertThat(trace.findings()).anyMatch(finding -> finding.code().equals("STALE_TEST_EVIDENCE"));
    }

    @Test
    void sensitiveBoundaryCreatesCriticalScopeFloor() {
        Project project = project(UUID.randomUUID());
        var trace = analyzer.analyze(project, List.of(), List.of(), List.of());

        var risk = analyzer.scopeRisk(project, trace, 0, 0, List.of("SENSITIVE_DATA"));

        assertThat(risk.score()).isGreaterThanOrEqualTo(75);
        assertThat(risk.band()).isEqualTo("CRITICAL");
    }

    @Test
    void personalContactDataAndDraftObjectiveApplyDocumentedScopeFloors() {
        Project project = project(UUID.randomUUID());
        TraceItem objective = item("O-01", TraceItemType.OBJECTIVE, "DRAFT", null, null, null);
        var trace = analyzer.analyze(project, List.of(objective), List.of(), List.of());

        assertThat(analyzer.scopeRisk(project, trace, 0, 0, List.of()).score()).isGreaterThanOrEqualTo(50);
        assertThat(analyzer.scopeRisk(project, trace, 0, 0, List.of("PERSONAL_CONTACT_DATA")).score()).isGreaterThanOrEqualTo(75);
    }

    @Test
    void readinessNeedsApprovedObjectiveLinkWithRationale() {
        Project project = project(UUID.randomUUID());
        TraceItem objective = item("O-01", TraceItemType.OBJECTIVE, "APPROVED", null, null, null);
        TraceItem requirement = item("R-01", TraceItemType.REQUIREMENT, "APPROVED", "MUST", "Response is under two seconds.", "TEST");
        TraceLink linkWithoutRationale = new TraceLink(UUID.randomUUID(), objective.id(), requirement.id(),
                "DECOMPOSES_TO", "ACTIVE", "");

        var trace = analyzer.analyze(project, List.of(objective, requirement), List.of(linkWithoutRationale), List.of());

        assertThat(trace.items().stream().filter(item -> item.id().equals(requirement.id())).findFirst().orElseThrow().readinessScore())
                .isLessThan(85);
        assertThat(trace.findings()).anyMatch(finding -> finding.code().equals("REQUIREMENT_WITHOUT_FEATURE"));
    }

    @Test
    void latestExecutionByTimestampControlsMustVerification() {
        Project project = project(UUID.randomUUID());
        TraceItem requirement = item("R-01", TraceItemType.REQUIREMENT, "APPROVED", "MUST", "Response is under two seconds.", "TEST");
        TraceItem test = item("T-01", TraceItemType.TEST_CASE, "APPROVED", "MANDATORY", null, null);
        Instant now = Instant.now();
        TestExecution newerFailure = new TestExecution(UUID.randomUUID(), test.id(), "FAILED", "new", true, true, now);
        TestExecution olderPass = new TestExecution(UUID.randomUUID(), test.id(), "PASSED", "old", true, true, now.minusSeconds(60));

        var trace = analyzer.analyze(project, List.of(requirement, test), List.of(link(requirement, test)),
                List.of(newerFailure, olderPass));

        assertThat(trace.coverage().passingCoverage()).isZero();
        assertThat(trace.coverage().priorityWeightedPassingCoverage()).isZero();
    }

    @Test
    void wrongTypedLinkCannotJustifyFeature() {
        Project project = project(UUID.randomUUID());
        TraceItem requirement = item("R-01", TraceItemType.REQUIREMENT, "APPROVED", "MUST", "Response is under two seconds.", "TEST");
        TraceItem feature = item("F-01", TraceItemType.FEATURE, "APPROVED", null, null, null);
        TraceLink wrong = new TraceLink(UUID.randomUUID(), requirement.id(), feature.id(), "VERIFIED_BY", "ACTIVE", "wrong direction semantics");

        var trace = analyzer.analyze(project, List.of(requirement, feature), List.of(wrong), List.of());

        assertThat(trace.findings()).anyMatch(finding -> finding.code().equals("UNJUSTIFIED_FEATURE"));
        assertThat(trace.findings()).anyMatch(finding -> finding.code().equals("INVALID_TRACE_RELATIONSHIP"));
    }

    private static Project project(UUID baseline) {
        return new Project(UUID.randomUUID(), "P-01", "Test project", ProjectStatus.ACTIVE, Recommendation.NEW,
                "CICS", baseline, baseline == null ? 0 : 1, List.of("Adviser"), Instant.now(), 0);
    }

    private static TraceItem item(String key, TraceItemType type, String status, String priority, String criteria, String method) {
        String description = type == TraceItemType.REQUIREMENT
                ? "The system shall let an authorized user record data and report an error safely." : "Test artifact";
        return new TraceItem(UUID.randomUUID(), key, type, key, description, status, priority, criteria, method, 1, 0);
    }

    private static TraceLink link(TraceItem source, TraceItem target) {
        String relationship = source.type() == TraceItemType.OBJECTIVE ? "DECOMPOSES_TO"
                : target.type() == TraceItemType.FEATURE ? "REALIZED_BY"
                : target.type() == TraceItemType.TEST_CASE ? "VERIFIED_BY" : "CONTRIBUTES_TO";
        return new TraceLink(UUID.randomUUID(), source.id(), target.id(), relationship, "ACTIVE", "test rationale");
    }
}

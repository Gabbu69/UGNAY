package com.ugnay.platform.changecontrol;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectStatus;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.ScopeRisk;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeImpactAnalyzerTest {
    @Test
    void cycleSafeTraversalReturnsEachArtifactOnceWithAPath() {
        UUID baseline = UUID.randomUUID();
        Project project = new Project(UUID.randomUUID(), "P", "Project", ProjectStatus.ACTIVE, Recommendation.NEW,
                "CICS", baseline, 1, List.of(), Instant.now(), 0);
        TraceItem requirement = item("R", TraceItemType.REQUIREMENT);
        TraceItem feature = item("F", TraceItemType.FEATURE);
        TraceItem test = item("T", TraceItemType.TEST_CASE);
        List<TraceLink> cycle = List.of(link(requirement, feature), link(feature, test), link(test, requirement));
        ChangeRequest change = new ChangeRequest(UUID.randomUUID(), project.id(), baseline, "Change R", "Measured change rationale",
                "IMPACT_REVIEW", List.of(requirement.id()), List.of(), Instant.now(), 0);
        ScopeRisk risk = new ScopeRisk(AssessmentStatus.ASSESSED, 20, "LOW", 5, 5, 5, 5, List.of());

        var preview = new ChangeImpactAnalyzer().preview(change, project, List.of(requirement, feature, test), cycle, risk);

        assertThat(preview.baselineCurrent()).isTrue();
        assertThat(preview.impactedArtifacts()).extracting(value -> value.itemId()).containsExactlyInAnyOrder(feature.id(), test.id());
        assertThat(preview.impactedArtifacts()).allMatch(value -> !value.path().isEmpty());
        assertThat(preview.impactedArtifacts().stream().filter(value -> value.itemId().equals(test.id())).findFirst().orElseThrow().evidenceBecomesStale()).isTrue();
    }

    @Test
    void staleBaselineIsExposedAndMustBeRecalculatedBeforeApproval() {
        UUID currentBaseline = UUID.randomUUID();
        Project project = new Project(UUID.randomUUID(), "P", "Project", ProjectStatus.ACTIVE, Recommendation.NEW,
                "CICS", currentBaseline, 2, List.of(), Instant.now(), 0);
        TraceItem requirement = item("R", TraceItemType.REQUIREMENT);
        ChangeRequest change = new ChangeRequest(UUID.randomUUID(), project.id(), UUID.randomUUID(), "Old change",
                "This request was based on the previous approved baseline.", "IMPACT_REVIEW", List.of(requirement.id()),
                List.of(), Instant.now(), 0);
        ScopeRisk risk = new ScopeRisk(AssessmentStatus.ASSESSED, 20, "LOW", 5, 5, 5, 5, List.of());

        var preview = new ChangeImpactAnalyzer().preview(change, project, List.of(requirement), List.of(), risk);

        assertThat(preview.baselineCurrent()).isFalse();
    }

    private static TraceItem item(String key, TraceItemType type) {
        return new TraceItem(UUID.randomUUID(), key, type, key, key, "APPROVED", null, null, null, 1, 0);
    }
    private static TraceLink link(TraceItem source, TraceItem target) {
        return new TraceLink(UUID.randomUUID(), source.id(), target.id(), "TRACE", "ACTIVE", "test");
    }
}

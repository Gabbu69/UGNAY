package com.ugnay.platform.workspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.LineageType;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UiContracts {
    private UiContracts() {}

    public record CurrentUser(String name, String initials, List<String> roles, String department) {}
    public record StudyView(UUID id, String code, String title, int year, String program, String status,
                            @JsonProperty("abstract") String abstractText, List<String> authors, List<String> keywords,
                            Double problemSimilarity, Double solutionSimilarity, Double objectiveOverlap,
                            Double confidence, String relationship, String matchReason, String excerpt,
                            boolean restricted) {}
    public record ProjectSummary(UUID id, String code, String title, String stage, Recommendation route,
                                 String department, String adviser, Instant updatedAt, int openFindings, Double health) {}
    public record TraceNode(UUID id, String code, String label, TraceItemType type, String status,
                            Double readiness, String priority) {}
    public record TraceEdge(UUID id, UUID source, UUID target, String relationship) {}
    public record FindingView(UUID id, String code, String rule, String title, String explanation,
                              List<String> evidence, String severity, FindingState state,
                              String nextAction, String itemCode) {}
    public record HealthView(String id, String label, AssessmentStatus state, Double score, double delta, String detail) {}
    public record ReviewView(UUID id, String eyebrow, String title, String summary, Instant due,
                             String risk, String owner, String action) {}
    public record LineageView(UUID id, String code, String title, int year, String relation,
                              String state, List<String> inherited) {}
    public record WorkspaceView(CurrentUser currentUser, ProjectSummary project, List<ProjectSummary> projects,
                                List<StudyView> studies, List<TraceNode> traceNodes, List<TraceEdge> traceEdges,
                                List<FindingView> findings, List<HealthView> health,
                                List<ReviewView> reviewQueue, List<LineageView> lineage,
                                boolean graphTruncated, int graphTotalNodes, int graphTotalEdges, Instant generatedAt) {}
    public record TraceGraphPage(List<TraceNode> nodes, List<TraceEdge> edges, int page, int size,
                                 int totalNodes, int totalEdges, boolean truncated) {}
    public record DiscoveryView(UUID id, AssessmentStatus status, Recommendation recommendation,
                                AssessmentStatus confidenceState, Double confidence,
                                List<StudyView> candidates, String algorithmVersion) {}
}

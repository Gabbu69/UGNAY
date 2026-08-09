package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.HealthDimension;
import com.ugnay.platform.shared.PlatformModels.Lineage;
import com.ugnay.platform.shared.PlatformModels.LineageEdge;
import com.ugnay.platform.shared.PlatformModels.LineageNode;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectHealth;
import com.ugnay.platform.shared.PlatformModels.ReviewQueueItem;
import com.ugnay.platform.shared.PlatformModels.Severity;
import com.ugnay.platform.shared.PlatformModels.Study;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import com.ugnay.platform.shared.PlatformModels.Traceability;
import com.ugnay.platform.identity.JdbcIdentityService;
import com.ugnay.platform.identity.ProjectAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public final class UiWorkspaceMapper {
    private final WorkspaceService workspace;
    private final JdbcIdentityService identities;
    private final CatalogueMetadataRepository catalogueMetadata;
    private final WorkflowActionService workflowActions;
    private final ProjectAccessService projectAccess;

    public UiWorkspaceMapper(WorkspaceService workspace, JdbcIdentityService identities,
                             CatalogueMetadataRepository catalogueMetadata, WorkflowActionService workflowActions,
                             ProjectAccessService projectAccess) {
        this.workspace = workspace;
        this.identities = identities;
        this.catalogueMetadata = catalogueMetadata;
        this.workflowActions = workflowActions;
        this.projectAccess = projectAccess;
    }

    public UiContracts.WorkspaceView workspace(Authentication authentication) {
        return workspace(authentication, null);
    }

    public UiContracts.WorkspaceView workspace(Authentication authentication, UUID selectedProjectId) {
        List<Project> available = workspace.projects().stream().filter(value -> projectAccess.canAccess(authentication, value.id())).toList();
        if (available.isEmpty()) {
            var emptyProject = new UiContracts.ProjectSummary(new UUID(0, 0), "UNASSESSED", "No persisted project yet",
                    "UNASSESSED", com.ugnay.platform.shared.PlatformModels.Recommendation.REVIEW_REQUIRED,
                    currentUser(authentication).department(), "Unassigned", Instant.now(), 0, 0);
            return new UiContracts.WorkspaceView(currentUser(authentication), emptyProject, List.of(), rankedStudies(Map.of()),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now());
        }
        Project current = selectedProjectId == null ? available.getFirst() : workspace.project(selectedProjectId);
        Traceability trace = workspace.traceability(current.id());
        ProjectHealth projectHealth = workspace.health(current.id());
        DiscoveryRun discovery = workspace.discoveries().stream().findFirst().orElse(null);
        Map<UUID, DiscoveryCandidate> matches = discovery == null ? Map.of() : discovery.candidates().stream()
                .collect(Collectors.toMap(DiscoveryCandidate::studyId, candidate -> candidate));
        UiContracts.CurrentUser currentUser = currentUser(authentication);
        List<UiContracts.ProjectSummary> projects = available.stream().map(this::project).toList();
        List<UiContracts.StudyView> studies = rankedStudies(matches);
        Map<UUID, TraceItem> byId = trace.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        List<UiContracts.TraceNode> nodes = trace.items().stream().map(item -> node(item, trace)).toList();
        List<UiContracts.TraceEdge> edges = trace.links().stream().map(link -> edge(link, byId)).toList();
        List<UiContracts.FindingView> findings = trace.findings().stream().map(finding -> finding(
                new Finding(finding.id(), finding.code(), finding.severity(), workflowActions.effectiveFindingState(current.id(), finding),
                        finding.title(), finding.explanation(), finding.nextAction(), finding.implicatedItemIds(), finding.ruleVersion()), byId)).toList();
        List<UiContracts.HealthView> health = projectHealth.dimensions().stream().map(this::health).toList();
        List<UiContracts.ReviewView> review = workspace.reviewQueue().stream().map(this::review).toList();
        List<UiContracts.LineageView> lineage = lineage(workspace.lineage(current.id()));
        return new UiContracts.WorkspaceView(currentUser, project(current), projects, studies, nodes, edges,
                findings, health, review, lineage, Instant.now());
    }

    private UiContracts.CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            return identities.userByEmail(authentication.getName())
                    .map(user -> new UiContracts.CurrentUser(user.displayName(), initials(user.displayName()), user.roles(), user.department()))
                    .orElseGet(() -> new UiContracts.CurrentUser(authentication.getName(), initials(authentication.getName()),
                            authentication.getAuthorities().stream()
                                    .map(authority -> authority.getAuthority()).filter(authority -> authority.startsWith("ROLE_"))
                                    .map(authority -> authority.substring("ROLE_".length())).toList(),
                            "University workspace"));
        }
        var demo = workspace.workspace().demoUser();
        return new UiContracts.CurrentUser(demo.displayName(), initials(demo.displayName()), demo.roles(), demo.department());
    }

    public List<UiContracts.StudyView> studies() {
        DiscoveryRun discovery = workspace.discoveries().stream().findFirst().orElse(null);
        Map<UUID, DiscoveryCandidate> matches = discovery == null ? Map.of() : discovery.candidates().stream().collect(Collectors.toMap(DiscoveryCandidate::studyId, value -> value));
        return rankedStudies(matches);
    }

    CatalogueMetadataRepository catalogueMetadata() { return catalogueMetadata; }

    private List<UiContracts.StudyView> rankedStudies(Map<UUID, DiscoveryCandidate> matches) {
        return workspace.studies().stream()
                .sorted(Comparator.comparingInt(value -> matches.containsKey(value.id()) ? matches.get(value.id()).rank() : Integer.MAX_VALUE))
                .map(value -> study(value, matches.get(value.id())))
                .toList();
    }

    public UiContracts.DiscoveryView discovery(DiscoveryRun run) {
        List<UiContracts.StudyView> candidates = run.candidates().stream().map(candidate ->
                study(workspace.study(candidate.studyId()), candidate)).toList();
        return new UiContracts.DiscoveryView(run.id(), run.assessmentStatus(), run.recommendation(), run.confidence(), candidates, run.algorithmVersion());
    }

    private UiContracts.ProjectSummary project(Project project) {
        ProjectHealth projectHealth = workspace.health(project.id());
        int open = projectHealth.openFindings();
        double score = projectHealth.dimensions().stream().filter(dimension -> dimension.score() != null)
                .mapToDouble(HealthDimension::score).min().orElse(0);
        String adviser = project.team().isEmpty() ? "Unassigned" : project.team().getLast();
        return new UiContracts.ProjectSummary(project.id(), project.code(), project.title(), project.status().name(), project.route(),
                project.department(), adviser, project.updatedAt(), open, score);
    }

    private UiContracts.StudyView study(Study study, DiscoveryCandidate candidate) {
        boolean restricted = "RESTRICTED".equals(study.visibility()) || "EMBARGOED".equals(study.visibility());
        double problem = candidate == null ? 0 : candidate.problemScore();
        double solution = candidate == null ? 0 : candidate.solutionScore();
        double objective = candidate == null ? 0 : candidate.objectiveScore();
        double confidence = candidate == null ? 0 : candidate.confidence();
        String reason = candidate == null || candidate.evidence().isEmpty()
                ? "No comparable evidence was available in the current discovery run."
                : "Matched " + candidate.evidence().stream().limit(3).map(evidence -> evidence.field()).collect(Collectors.joining(", "))
                  + "; inspect component scores before making an academic decision.";
        String excerpt = candidate == null || candidate.evidence().isEmpty() ? study.abstractText()
                : candidate.evidence().getFirst().studyExcerpt();
        if (restricted) {
            reason = "Protected fields influenced the score, but matched passages are hidden by catalogue visibility policy.";
            excerpt = "Restricted evidence excerpt. Request curator-authorized access to review the source text.";
        }
        CatalogueMetadataRepository.Metadata metadata = catalogueMetadata.metadata(study.id());
        return new UiContracts.StudyView(study.id(), study.institutionalCode(), study.title(), parseYear(study.academicYear()),
                metadata.program(), study.lifecycleStatus(), restricted ? "Restricted catalogue record." : study.abstractText(),
                restricted ? List.of() : metadata.authors(), study.keywords(),
                problem, solution, objective, confidence, metadata.relationship(), reason, excerpt,
                restricted);
    }

    private UiContracts.TraceNode node(TraceItem item, Traceability trace) {
        String status = item.lifecycleStatus();
        if (item.type() == TraceItemType.TEST_CASE) {
            status = trace.executions().stream().filter(execution -> execution.testItemId().equals(item.id())).findFirst()
                    .map(execution -> !execution.current() ? "STALE" : "PASSED".equals(execution.status()) ? "PASSING" : "MISSING")
                    .orElse("MISSING");
        }
        Double readiness = item.type() == TraceItemType.REQUIREMENT ? item.readinessScore() : null;
        return new UiContracts.TraceNode(item.id(), item.key(), item.title(), item.type(), status, readiness, item.priority());
    }

    private static UiContracts.TraceEdge edge(TraceLink link, Map<UUID, TraceItem> byId) {
        TraceItem source = byId.get(link.sourceId());
        TraceItem target = byId.get(link.targetId());
        String relationship = "DERIVES";
        if (source != null && target != null) {
            if (source.type() == TraceItemType.PROBLEM) relationship = "ADDRESSES";
            else if (target.type() == TraceItemType.FEATURE) relationship = "REALIZES";
            else if (target.type() == TraceItemType.TEST_CASE) relationship = "VERIFIES";
            else if (target.type() == TraceItemType.OUTPUT) relationship = "PRODUCES";
        }
        return new UiContracts.TraceEdge(link.id(), link.sourceId(), link.targetId(), relationship);
    }

    private static UiContracts.FindingView finding(Finding finding, Map<UUID, TraceItem> byId) {
        List<String> evidence = finding.implicatedItemIds().stream().map(id -> byId.containsKey(id) ? byId.get(id).key() : id.toString()).toList();
        String severity = switch (finding.severity()) {
            case INFO, LOW -> "INFO";
            case MODERATE -> "WARNING";
            case HIGH -> "HIGH";
            case CRITICAL -> "CRITICAL";
        };
        return new UiContracts.FindingView(finding.id(), finding.code(), finding.ruleVersion(), finding.title(), finding.explanation(),
                evidence, severity, finding.state(), finding.nextAction(), evidence.isEmpty() ? "PROJECT" : evidence.getFirst());
    }

    private UiContracts.HealthView health(HealthDimension dimension) {
        return new UiContracts.HealthView(dimension.key(), dimension.label(), dimension.score(), 0, dimension.explanation());
    }

    private UiContracts.ReviewView review(ReviewQueueItem item) {
        String risk = item.severity() == Severity.CRITICAL ? "CRITICAL" : item.severity() == Severity.HIGH ? "HIGH"
                : item.severity() == Severity.MODERATE ? "MODERATE" : "LOW";
        return new UiContracts.ReviewView(item.id(), item.type().replace('_', ' '), item.title(), item.reason(), item.dueAt(), risk,
                item.requiredRole(), "Open evidence");
    }

    private static List<UiContracts.LineageView> lineage(Lineage lineage) {
        Map<UUID, LineageEdge> incoming = new HashMap<>();
        lineage.edges().forEach(edge -> incoming.put(edge.targetId(), edge));
        List<UiContracts.LineageView> result = new ArrayList<>();
        for (LineageNode node : lineage.nodes()) {
            LineageEdge edge = incoming.get(node.id());
            String relation = edge == null ? "ORIGIN" : edge.type().name();
            String state = node.current() ? "ACTIVE" : "COMPLETED".equals(node.status()) ? "COMPLETE" : "AVAILABLE";
            List<String> inherited = edge == null ? List.of("Original problem evidence") : List.of(edge.rationale());
            result.add(new UiContracts.LineageView(node.id(), node.kind() + "-" + (result.size() + 1), node.title(),
                    parseYear(node.year()), relation, state, inherited));
        }
        return result;
    }

    private static String initials(String value) {
        return java.util.Arrays.stream(value.split("\\s+")).filter(part -> !part.isBlank()).limit(2)
                .map(part -> part.substring(0, 1).toUpperCase()).collect(Collectors.joining());
    }

    private static int parseYear(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(20\\d{2})").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}

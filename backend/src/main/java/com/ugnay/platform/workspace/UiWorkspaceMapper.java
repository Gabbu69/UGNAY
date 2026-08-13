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
import com.ugnay.platform.identity.StudyVisibilityPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public final class UiWorkspaceMapper {
    private static final int WORKSPACE_PROJECT_LIMIT = 50;
    private static final int WORKSPACE_STUDY_LIMIT = 100;
    private static final int WORKSPACE_REVIEW_LIMIT = 50;
    private final WorkspaceService workspace;
    private final JdbcIdentityService identities;
    private final CatalogueMetadataRepository catalogueMetadata;
    private final WorkflowActionService workflowActions;
    private final ProjectAccessService projectAccess;
    private final StudyVisibilityPolicy studyVisibility;
    private final int maxGraphNodes;
    private final int maxGraphEdges;

    public UiWorkspaceMapper(WorkspaceService workspace, JdbcIdentityService identities,
                             CatalogueMetadataRepository catalogueMetadata, WorkflowActionService workflowActions,
                             ProjectAccessService projectAccess, StudyVisibilityPolicy studyVisibility,
                             @Value("${ugnay.graph.max-nodes:2000}") int maxGraphNodes,
                             @Value("${ugnay.graph.max-edges:4000}") int maxGraphEdges) {
        this.workspace = workspace;
        this.identities = identities;
        this.catalogueMetadata = catalogueMetadata;
        this.workflowActions = workflowActions;
        this.projectAccess = projectAccess;
        this.studyVisibility = studyVisibility;
        this.maxGraphNodes = Math.max(1, maxGraphNodes);
        this.maxGraphEdges = Math.max(1, maxGraphEdges);
    }

    public UiContracts.WorkspaceView workspace(Authentication authentication) {
        return workspace(authentication, null);
    }

    public UiContracts.WorkspaceView workspace(Authentication authentication, UUID selectedProjectId) {
        boolean authenticated = authenticated(authentication);
        List<Project> available = authenticated
                ? workspace.projects().stream().filter(value -> projectAccess.canAccess(authentication, value.id())).toList()
                : List.of();
        List<Project> boundedProjects = available.stream().limit(WORKSPACE_PROJECT_LIMIT).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (selectedProjectId != null && boundedProjects.stream().noneMatch(project -> project.id().equals(selectedProjectId))) {
            available.stream().filter(project -> project.id().equals(selectedProjectId)).findFirst().ifPresent(project -> {
                if (boundedProjects.size() == WORKSPACE_PROJECT_LIMIT) boundedProjects.removeLast();
                boundedProjects.add(project);
            });
        }
        List<UiContracts.ProjectSummary> projects = boundedProjects.stream().map(this::project).toList();
        if (available.isEmpty() || selectedProjectId == null) {
            return new UiContracts.WorkspaceView(currentUser(authentication), null, projects, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, 0, 0, Instant.now());
        }
        Project current = available.stream()
                .filter(project -> project.id().equals(selectedProjectId)).findFirst()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("The selected project is not available to this account."));
        Traceability trace = workspace.traceability(current.id());
        ProjectHealth projectHealth = workspace.health(current.id());
        UUID proposalId = workspace.proposalIdForProject(current.id());
        DiscoveryRun discovery = workspace.latestDiscoveryForProposal(proposalId).orElse(null);
        Map<UUID, DiscoveryCandidate> matches = discovery == null ? Map.of() : discovery.candidates().stream()
                .collect(Collectors.toMap(DiscoveryCandidate::studyId, candidate -> candidate));
        UiContracts.CurrentUser currentUser = currentUser(authentication);
        List<UiContracts.StudyView> studies = rankedStudies(authentication, matches);
        Map<UUID, TraceItem> byId = trace.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        List<UiContracts.TraceNode> allNodes = trace.items().stream().map(item -> node(item, trace)).toList();
        List<UiContracts.TraceNode> nodes = allNodes.stream().limit(maxGraphNodes).toList();
        Set<UUID> visibleNodeIds = nodes.stream().map(UiContracts.TraceNode::id).collect(Collectors.toSet());
        List<TraceLink> visibleLinks = trace.links().stream()
                .filter(link -> visibleNodeIds.contains(link.sourceId()) && visibleNodeIds.contains(link.targetId())).toList();
        List<UiContracts.TraceEdge> edges = visibleLinks.stream().limit(maxGraphEdges).map(link -> edge(link, byId)).toList();
        List<UiContracts.FindingView> findings = trace.findings().stream().map(finding -> finding(
                new Finding(finding.id(), finding.code(), finding.severity(), workflowActions.effectiveFindingState(current.id(), finding),
                        finding.title(), finding.explanation(), finding.nextAction(), finding.implicatedItemIds(), finding.ruleVersion()), byId)).toList();
        List<UiContracts.HealthView> health = projectHealth.dimensions().stream().map(this::health).toList();
        List<UiContracts.ReviewView> review = workspace.reviewQueue().stream()
                .filter(item -> current.code().equals(item.projectCode())).limit(WORKSPACE_REVIEW_LIMIT).map(this::review).toList();
        List<UiContracts.LineageView> lineage = lineage(workspace.lineage(current.id()));
        return new UiContracts.WorkspaceView(currentUser, project(current), projects, studies, nodes, edges,
                findings, health, review, lineage,
                allNodes.size() > nodes.size() || visibleLinks.size() > edges.size(), allNodes.size(), trace.links().size(), Instant.now());
    }

    public UiContracts.TraceGraphPage traceGraph(UUID projectId, int requestedPage, int requestedSize) {
        Traceability trace = workspace.traceability(projectId);
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, maxGraphNodes));
        int from = (int) Math.min(trace.items().size(), (long) page * size);
        int to = Math.min(trace.items().size(), from + size);
        List<TraceItem> pageItems = trace.items().subList(from, to);
        Set<UUID> ids = pageItems.stream().map(TraceItem::id).collect(Collectors.toSet());
        Map<UUID, TraceItem> byId = trace.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        List<TraceLink> relevant = trace.links().stream()
                .filter(link -> ids.contains(link.sourceId()) && ids.contains(link.targetId())).toList();
        List<UiContracts.TraceNode> nodes = pageItems.stream().map(item -> node(item, trace)).toList();
        List<UiContracts.TraceEdge> edges = relevant.stream().limit(maxGraphEdges).map(link -> edge(link, byId)).toList();
        boolean truncated = to < trace.items().size() || relevant.size() > edges.size();
        return new UiContracts.TraceGraphPage(nodes, edges, page, size, trace.items().size(), trace.links().size(), truncated);
    }

    private UiContracts.CurrentUser currentUser(Authentication authentication) {
        if (authenticated(authentication)) {
            return identities.userByEmail(authentication.getName())
                    .map(user -> new UiContracts.CurrentUser(user.displayName(), initials(user.displayName()), user.roles(), user.department()))
                    .orElseGet(() -> new UiContracts.CurrentUser(authentication.getName(), initials(authentication.getName()),
                            authentication.getAuthorities().stream()
                                    .map(authority -> authority.getAuthority()).filter(authority -> authority.startsWith("ROLE_"))
                                    .map(authority -> authority.substring("ROLE_".length())).toList(),
                            "University workspace"));
        }
        return new UiContracts.CurrentUser("Not signed in", "", List.of(), "Unavailable");
    }

    public List<UiContracts.StudyView> studies() {
        return List.of();
    }

    public List<UiContracts.StudyView> studies(Authentication authentication) {
        StudyVisibilityPolicy.Scope scope = studyVisibility.scope(authentication);
        return workspace.studies().stream()
                .filter(value -> studyVisibility.canView(scope, value.visibility(), value.department()))
                .sorted(Comparator.comparing(Study::title, String.CASE_INSENSITIVE_ORDER))
                .map(value -> study(value, null, !scope.curator()))
                .toList();
    }

    CatalogueMetadataRepository catalogueMetadata() { return catalogueMetadata; }

    private List<UiContracts.StudyView> rankedStudies(Authentication authentication, Map<UUID, DiscoveryCandidate> matches) {
        if (matches.isEmpty()) return List.of();
        StudyVisibilityPolicy.Scope scope = studyVisibility.scope(authentication);
        return workspace.studies().stream()
                .filter(value -> matches.containsKey(value.id()))
                .filter(value -> studyVisibility.canView(scope, value.visibility(), value.department()))
                .sorted(Comparator.comparingInt(value -> matches.containsKey(value.id()) ? matches.get(value.id()).rank() : Integer.MAX_VALUE))
                .limit(WORKSPACE_STUDY_LIMIT)
                .map(value -> study(value, matches.get(value.id()), !scope.curator()))
                .toList();
    }

    public UiContracts.DiscoveryView discovery(DiscoveryRun run) {
        List<UiContracts.StudyView> candidates = run.candidates().stream().map(candidate ->
                study(workspace.study(candidate.studyId()), candidate)).toList();
        return new UiContracts.DiscoveryView(run.id(), run.assessmentStatus(), run.recommendation(),
                run.confidenceState(), run.confidence(), candidates, run.algorithmVersion());
    }

    public UiContracts.DiscoveryView discovery(DiscoveryRun run, Authentication authentication) {
        List<UiContracts.StudyView> candidates = run.candidates().stream().filter(candidate -> {
            Study study = workspace.study(candidate.studyId());
            return studyVisibility.canView(authentication, study.visibility(), study.department());
        }).map(candidate -> study(workspace.study(candidate.studyId()), candidate,
                !studyVisibility.scope(authentication).curator())).toList();
        return new UiContracts.DiscoveryView(run.id(), run.assessmentStatus(), run.recommendation(),
                run.confidenceState(), run.confidence(), candidates, run.algorithmVersion());
    }

    private UiContracts.ProjectSummary project(Project project) {
        ProjectHealth projectHealth = workspace.health(project.id());
        int open = projectHealth.openFindings();
        Double score = projectHealth.dimensions().stream().map(HealthDimension::score)
                .filter(java.util.Objects::nonNull).min(Double::compareTo).orElse(null);
        String adviser = project.team().isEmpty() ? "Unassigned" : project.team().getLast();
        return new UiContracts.ProjectSummary(project.id(), project.code(), project.title(), project.status().name(), project.route(),
                project.department(), adviser, project.updatedAt(), open, score);
    }

    private UiContracts.StudyView study(Study study, DiscoveryCandidate candidate) {
        return study(study, candidate, true);
    }

    private UiContracts.StudyView study(Study study, DiscoveryCandidate candidate, boolean protectRestricted) {
        boolean restricted = protectRestricted
                && ("RESTRICTED".equals(study.visibility()) || "EMBARGOED".equals(study.visibility()));
        Double problem = candidate == null ? null : candidate.problemScore();
        Double solution = candidate == null ? null : candidate.solutionScore();
        Double objective = candidate == null ? null : candidate.objectiveScore();
        Double confidence = candidate == null ? null : candidate.confidence();
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
                metadata.program() == null ? "Unavailable" : metadata.program(), study.lifecycleStatus(), restricted ? "Restricted catalogue record." : study.abstractText(),
                restricted ? List.of() : metadata.authors(), study.keywords(),
                problem, solution, objective, confidence, metadata.relationship() == null ? "UNAVAILABLE" : metadata.relationship(), reason, excerpt,
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
        return new UiContracts.HealthView(dimension.key(), dimension.label(), dimension.status(), dimension.score(), 0, dimension.explanation());
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

    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && authentication.getName() != null
                && !authentication.getName().isBlank() && !"anonymousUser".equals(authentication.getName());
    }

    private static int parseYear(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(20\\d{2})").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}

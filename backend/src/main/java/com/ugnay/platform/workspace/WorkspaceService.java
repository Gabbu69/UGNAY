package com.ugnay.platform.workspace;

import com.ugnay.platform.analytics.AlignmentAnalyzer;
import com.ugnay.platform.changecontrol.ChangeImpactAnalyzer;
import com.ugnay.platform.continuity.LineageValidator;
import com.ugnay.platform.discovery.SimilarityEngine;
import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.ChangeOperation;
import com.ugnay.platform.shared.PlatformModels.ChangeOperationType;
import com.ugnay.platform.shared.PlatformModels.ChangeDecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.CompletionPackage;
import com.ugnay.platform.shared.PlatformModels.ContinuityCriterion;
import com.ugnay.platform.shared.PlatformModels.ContinuationItem;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.ImpactPreview;
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
import com.ugnay.platform.shared.PlatformModels.UserSummary;
import com.ugnay.platform.shared.PlatformModels.Workspace;
import com.ugnay.platform.shared.JdbcAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WorkspaceService {
    private final SimilarityEngine similarity;
    private final AlignmentAnalyzer alignment;
    private final ChangeImpactAnalyzer impactAnalyzer;
    private final LineageValidator lineageValidator;
    private final String algorithmVersion;
    private final JdbcAuditService audit;
    private final JdbcWorkspaceStore store;
    private final RoutingEvidenceRepository routingEvidence;
    private final boolean seedSynthetic;
    private boolean seeding = true;

    private final Map<UUID, Study> studies = new ConcurrentHashMap<>();
    private final Map<UUID, ProblemCase> problems = new ConcurrentHashMap<>();
    private final Map<UUID, Proposal> proposals = new ConcurrentHashMap<>();
    private final Map<UUID, DiscoveryRun> discoveries = new ConcurrentHashMap<>();
    private final Map<UUID, ProposalDecision> decisions = new ConcurrentHashMap<>();
    private final Map<UUID, Project> projects = new ConcurrentHashMap<>();
    private final Map<UUID, Traceability> traces = new ConcurrentHashMap<>();
    private final Map<UUID, ScopeRisk> scopeRisks = new ConcurrentHashMap<>();
    private final Map<UUID, ProjectHealth> health = new ConcurrentHashMap<>();
    private final Map<UUID, ChangeRequest> changes = new ConcurrentHashMap<>();
    private final Map<UUID, ImpactPreview> impacts = new ConcurrentHashMap<>();
    private final Map<UUID, CompletionPackage> packages = new ConcurrentHashMap<>();
    private final Map<UUID, Lineage> lineages = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> proposalProblemIds = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> projectProposalIds = new ConcurrentHashMap<>();
    private final List<ReviewQueueItem> reviewQueue = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final UserSummary demoUser = new UserSummary(id("user-admin"), "Dr. Amara Reyes", "admin@ugnay.local",
            "College of Information and Computing Sciences", List.of("CURATOR", "COORDINATOR", "ADVISER"));

    @Autowired
    public WorkspaceService(SimilarityEngine similarity, AlignmentAnalyzer alignment,
                            ChangeImpactAnalyzer impactAnalyzer, LineageValidator lineageValidator,
                            @Value("${ugnay.discovery.algorithm-version:hybrid-v1.0.0}") String algorithmVersion,
                            JdbcAuditService audit, JdbcWorkspaceStore store, RoutingEvidenceRepository routingEvidence,
                            @Value("${ugnay.dataset-mode:SYNTHETIC_DEMO}") String datasetMode) {
        this.similarity = similarity;
        this.alignment = alignment;
        this.impactAnalyzer = impactAnalyzer;
        this.lineageValidator = lineageValidator;
        this.algorithmVersion = algorithmVersion;
        this.audit = audit;
        this.store = store;
        this.routingEvidence = routingEvidence;
        this.seedSynthetic = "SYNTHETIC_DEMO".equalsIgnoreCase(datasetMode);
    }

    /** Test-friendly constructor retained for deterministic service restart tests. */
    public WorkspaceService(SimilarityEngine similarity, AlignmentAnalyzer alignment,
                            ChangeImpactAnalyzer impactAnalyzer, LineageValidator lineageValidator,
                            String algorithmVersion, JdbcAuditService audit, JdbcWorkspaceStore store) {
        this.similarity = similarity;
        this.alignment = alignment;
        this.impactAnalyzer = impactAnalyzer;
        this.lineageValidator = lineageValidator;
        this.algorithmVersion = algorithmVersion;
        this.audit = audit;
        this.store = store;
        this.routingEvidence = null;
        this.seedSynthetic = true;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public synchronized void initializePersistence() {
        clearWorkspace();
        if (store.isEmpty()) {
            if (seedSynthetic) {
                seed();
                persistWorkspace();
            }
        } else {
            loadWorkspace(store.load());
        }
        this.seeding = false;
    }

    public Workspace workspace() {
        return new Workspace(
                "UGNAY: Research Continuity and Project Alignment Platform",
                "v1",
                demoUser,
                studies(),
                problems(),
                proposals(),
                discoveries(),
                decisions(),
                projects(),
                traces.values().stream().toList(),
                health.values().stream().toList(),
                changes(),
                impacts.values().stream().toList(),
                lineages.values().stream().toList(),
                packages.values().stream().toList(),
                reviewQueue(),
                algorithmDisclosure());
    }

    public Map<String, Object> dashboard() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("publishedStudies", studies.size());
        counts.put("activeProjects", (int) projects.values().stream().filter(project -> project.status() != ProjectStatus.COMPLETED).count());
        counts.put("openFindings", traces.values().stream().mapToInt(trace -> (int) trace.findings().stream().filter(f -> f.state().name().equals("OPEN")).count()).sum());
        counts.put("pendingReviews", reviewQueue.size());
        return Map.of(
                "counts", counts,
                "projectHealth", health.values().stream().toList(),
                "reviewQueue", reviewQueue(),
                "recentStudies", studies().stream().limit(3).toList(),
                "activeProjects", projects());
    }

    public List<Study> studies() {
        return studies.values().stream()
                .sorted(Comparator.comparing(Study::academicYear,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Study::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }
    public Study study(UUID id) { return required(studies, id, "Study"); }
    public List<ProblemCase> problems() { return problems.values().stream().sorted(Comparator.comparing(ProblemCase::createdAt).reversed()).toList(); }
    public ProblemCase problem(UUID id) { return required(problems, id, "Problem case"); }
    public List<Proposal> proposals() { return proposals.values().stream().sorted(Comparator.comparing(Proposal::submittedAt).reversed()).toList(); }
    public List<JdbcWorkspaceStore.ProposalObjectiveRecord> proposalObjectiveRecords(UUID proposalId) {
        proposal(proposalId);
        return store.proposalObjectiveRecords(proposalId);
    }
    public Proposal proposal(UUID id) { return required(proposals, id, "Proposal"); }
    public List<DiscoveryRun> discoveries() { return discoveries.values().stream().sorted(Comparator.comparing(DiscoveryRun::createdAt).reversed()).toList(); }
    public DiscoveryRun discovery(UUID id) { return required(discoveries, id, "Discovery run"); }
    public List<ProposalDecision> decisions() { return decisions.values().stream().sorted(Comparator.comparing(ProposalDecision::decidedAt).reversed()).toList(); }
    public List<Project> projects() { return projects.values().stream().sorted(Comparator.comparing(Project::updatedAt).reversed()).toList(); }
    public Project project(UUID id) { return required(projects, id, "Project"); }
    public UUID proposalIdForProject(UUID projectId) { return required(projectProposalIds, projectId, "Project proposal"); }
    public Optional<DiscoveryRun> latestDiscoveryForProposal(UUID proposalId) {
        return discoveries().stream().filter(run -> run.proposalId().equals(proposalId)).findFirst();
    }
    public Traceability traceability(UUID projectId) { return required(traces, projectId, "Traceability workspace"); }
    public ProjectHealth health(UUID projectId) { return required(health, projectId, "Project health"); }
    public ScopeRisk scopeRisk(UUID projectId) { return required(scopeRisks, projectId, "Scope risk"); }
    public Lineage lineage(UUID projectId) { return required(lineages, projectId, "Project lineage"); }
    public CompletionPackage completionPackage(UUID projectId) { return required(packages, projectId, "Completion package"); }
    public List<ChangeRequest> changes() { return changes.values().stream().sorted(Comparator.comparing(ChangeRequest::createdAt).reversed()).toList(); }
    public ChangeRequest change(UUID id) { return required(changes, id, "Change request"); }
    public ImpactPreview impact(UUID changeId) { return required(impacts, changeId, "Impact preview"); }
    public List<ReviewQueueItem> reviewQueue() { return reviewQueue.stream().sorted(Comparator.comparing(ReviewQueueItem::dueAt)).toList(); }

    public List<JdbcWorkspaceStore.ResearchReviewRecord> researchReviews(UUID projectId) {
        project(projectId);
        return store.researchReviews(projectId);
    }

    public JdbcWorkspaceStore.ResearchReviewRecord researchReview(UUID projectId, UUID reviewId) {
        project(projectId);
        return store.researchReview(projectId, reviewId);
    }

    @Transactional
    public synchronized ReviewActionResult appendReviewEvent(UUID projectId, UUID reviewId, String eventType,
            String message, String evidenceLocation, String actorEmail) {
        Project current = editableProject(projectId);
        var review = store.appendResearchReviewEvent(projectId, reviewId, eventType,
                requiredText(message, "Review message"), evidenceLocation, actorEmail, Instant.now());
        Project touched = touchProject(current);
        audit.append(actorEmail, eventType, "RESEARCH_REVIEW", reviewId,
                "Appended an actor-attributed project review event.",
                Map.of("projectId", projectId.toString(), "reviewStatus", review.status()));
        return new ReviewActionResult(touched, review);
    }

    public record ReviewActionResult(Project project, JdbcWorkspaceStore.ResearchReviewRecord review) {}

    void discardRolledBackIntake(UUID problemId, UUID proposalId) {
        problems.remove(problemId);
        if (proposalId != null) {
            proposals.remove(proposalId);
            proposalProblemIds.remove(proposalId);
        }
    }

    @Transactional
    public synchronized Study importStudy(String code, String title, String academicYear, String abstractText,
                                          String problemStatement, List<String> objectives, List<String> keywords,
                                          String methodology, String features, String stakeholders, String siteContext) {
        return importStudy(code, title, academicYear, abstractText, problemStatement, objectives, keywords,
                methodology, features, stakeholders, siteContext, null, null, null, null, null, null, null);
    }

    @Transactional
    public synchronized Study importStudy(String code, String title, String academicYear, String abstractText,
                                          String problemStatement, List<String> objectives, List<String> keywords,
                                          String methodology, String features, String stakeholders, String siteContext,
                                          String department, String dataSources, String technology, String intendedUsers,
                                          String visibility, String lifecycleStatus, String actorEmail) {
        if (studies.values().stream().anyMatch(study -> study.institutionalCode().equalsIgnoreCase(code))) {
            throw new IllegalArgumentException("A study with institutional code " + code + " already exists.");
        }
        Study study = new Study(UUID.randomUUID(), code, title, blankToNull(academicYear),
                blankToNull(department),
                blank(lifecycleStatus) ? "INCOMPLETE" : lifecycleStatus.strip().toUpperCase(),
                blank(visibility) ? "RESTRICTED" : visibility.strip().toUpperCase(),
                abstractText, problemStatement, safeList(objectives), safeList(keywords), methodology, features,
                blank(dataSources) ? "" : dataSources.strip(), blank(technology) ? "" : technology.strip(),
                blank(intendedUsers) ? "" : intendedUsers.strip(), stakeholders, siteContext, List.of());
        store.saveStudy(study);
        audit.append(actorEmail, "STUDY_METADATA_IMPORTED", "STUDY", study.id(), "Imported reviewed catalogue metadata.", Map.of("code", code));
        afterCommit(() -> studies.put(study.id(), study));
        return study;
    }

    @Transactional
    public synchronized ProblemCase createProblem(String title, String problemStatement, String stakeholder,
                                                  String affectedUsers, String siteContext, String desiredOutcome,
                                                  String constraints, String privacyClassification, int evidenceCount) {
        return createProblem(title, problemStatement, stakeholder, affectedUsers, siteContext, desiredOutcome,
                constraints, privacyClassification, evidenceCount, null);
    }

    @Transactional
    public synchronized ProblemCase createProblem(String title, String problemStatement, String stakeholder,
                                                  String affectedUsers, String siteContext, String desiredOutcome,
                                                  String constraints, String privacyClassification, int evidenceCount,
                                                  String actorEmail) {
        return createProblem(title, problemStatement, stakeholder, affectedUsers, siteContext, desiredOutcome,
                constraints, privacyClassification, evidenceCount, actorEmail, true);
    }

    @Transactional
    synchronized ProblemCase createProblem(String title, String problemStatement, String stakeholder,
                                            String affectedUsers, String siteContext, String desiredOutcome,
                                            String constraints, String privacyClassification, int evidenceCount,
                                            String actorEmail, boolean createLegacyEvidenceRows) {
        Instant now = Instant.now();
        ProblemCase problem = new ProblemCase(UUID.randomUUID(), title, problemStatement, stakeholder, affectedUsers,
                siteContext, desiredOutcome, constraints, privacyClassification, evidenceCount > 0 ? "READY" : "DRAFT",
                Math.max(0, evidenceCount), now, 0);
        problems.put(problem.id(), problem);
        store.saveProblem(problem, actorEmail, createLegacyEvidenceRows);
        audit.append(actorEmail, "PROBLEM_CREATED", "PROBLEM", problem.id(), "Created a structured problem case.", Map.of("evidenceCount", problem.evidenceCount()));
        return problem;
    }

    @Transactional
    public synchronized Proposal createProposal(UUID problemId, String title, List<String> objectives,
                                                String proposedSolution, String methodology, String dataSources,
                                                String technology, String intendedUsers) {
        return createProposal(problemId, title, objectives, proposedSolution, methodology, dataSources,
                technology, intendedUsers, null);
    }

    @Transactional
    public synchronized Proposal createProposal(UUID problemId, String title, List<String> objectives,
                                                String proposedSolution, String methodology, String dataSources,
                                                String technology, String intendedUsers, String actorEmail) {
        ProblemCase problem = problem(problemId);
        Proposal proposal = new Proposal(UUID.randomUUID(), title, problem.problemStatement(), problem.stakeholder(),
                problem.affectedUsers(), problem.siteContext(), problem.desiredOutcome(), problem.constraints(),
                problem.privacyClassification(), safeList(objectives), proposedSolution, methodology, dataSources,
                technology, intendedUsers, "SUBMITTED", Instant.now(), 0);
        proposals.put(proposal.id(), proposal);
        proposalProblemIds.put(proposal.id(), problemId);
        store.saveProposal(proposal, problemId, actorEmail);
        audit.append(actorEmail, "PROPOSAL_SUBMITTED", "PROPOSAL", proposal.id(), "Submitted a proposal for discovery review.", Map.of("problemId", problemId.toString()));
        return proposal;
    }

    @Transactional
    public synchronized DiscoveryRun runDiscovery(Proposal input) {
        return runDiscovery(input, null);
    }

    @Transactional
    public synchronized DiscoveryRun runDiscovery(Proposal input, String actorEmail) {
        if (!seeding && !store.proposalExists(input.id())) persistDiscoveryInput(input, actorEmail);
        List<String> checklist = new ArrayList<>(intakeChecklist(input));
        List<DiscoveryCandidate> candidates = seeding ? similarity.rankLexical(input, studies()) : similarity.rank(input, studies());
        DiscoveryCandidate top = candidates.isEmpty() ? null : candidates.getFirst();
        AssessmentStatus status = !seeding && similarity.lastRunUsedSemanticEvidence()
                ? AssessmentStatus.ASSESSED : AssessmentStatus.PARTIAL;
        Recommendation recommendation;
        String explanation;
        if (!checklist.isEmpty()) {
            recommendation = Recommendation.REVIEW_REQUIRED;
            explanation = "Required intake evidence is incomplete; revise the proposal before academic routing.";
        } else if (status == AssessmentStatus.PARTIAL) {
            recommendation = Recommendation.REVIEW_REQUIRED;
            explanation = "Lexical and bilingual-concept evidence is available, but the semantic model is unavailable. A human review is required.";
        } else if (top != null && top.exactMatch()) {
            recommendation = Recommendation.REVIEW_REQUIRED;
            explanation = "An exact normalized title match requires identifier and context review before routing.";
            checklist.add("Confirm whether the exact title or institutional identifier represents the same scholarly record.");
        } else if (top == null || top.problemScore() < 65) {
            recommendation = Recommendation.NEW;
            explanation = "No candidate reaches the strong problem-overlap threshold.";
        } else {
            Study predecessor = studies.get(top.studyId());
            double novelty = predecessor == null ? 100
                    : similarity.objectiveNoveltyPercentage(input.objectives(), predecessor.objectives());
            if (top.problemScore() >= 80 && top.objectiveScore() >= 75 && top.solutionScore() >= 70
                    && novelty < 25 && top.confidence() >= 75) {
                recommendation = Recommendation.POSSIBLE_DUPLICATE;
                explanation = "All overlap, novelty, and confidence gates were met. This is a review flag, never a plagiarism or rejection decision.";
            } else if (predecessor != null && (predecessor.lifecycleStatus().equals("SUSPENDED") || predecessor.lifecycleStatus().equals("INCOMPLETE"))) {
                RoutingEvidenceRepository.RouteAssessment route = routeAssessment(input.id(), predecessor.id());
                recommendation = route.continuationReady() ? Recommendation.CONTINUE : Recommendation.REVIEW_REQUIRED;
                explanation = route.continuationReady()
                        ? "The unfinished predecessor, objective mappings, and confirmed code/data access satisfy the continuation gates."
                        : "The strongest predecessor is unfinished, but structured continuation evidence is incomplete.";
                if (predecessor.continuationItems().stream().noneMatch(item -> "OPEN".equals(item.status()))) {
                    checklist.add("Identify an open limitation, recommendation, or unfinished item on the predecessor.");
                }
                if (route.continuationState() != AssessmentStatus.ASSESSED || route.continuationCoverage() == null
                        || route.continuationCoverage() < 60) {
                    checklist.add("Link at least 60% of proposed objectives to open predecessor continuation items.");
                }
                if (!Boolean.TRUE.equals(route.codeAccess()) || !Boolean.TRUE.equals(route.dataAccess())) {
                    checklist.add("Confirm repository, code, and required data access before approving CONTINUE.");
                }
            } else if (predecessor != null && "COMPLETED".equals(predecessor.lifecycleStatus())
                    && predecessor.continuationItems().stream().anyMatch(item -> item.type().equals("LIMITATION") || item.type().equals("RECOMMENDATION"))) {
                RoutingEvidenceRepository.RouteAssessment route = routeAssessment(input.id(), predecessor.id());
                recommendation = route.improvementReady() ? Recommendation.IMPROVE : Recommendation.REVIEW_REQUIRED;
                explanation = route.improvementReady()
                        ? "The completed predecessor and measurable limitation-based improvement claim satisfy the improvement gates."
                        : "A completed predecessor and documented improvement basis exist, but measurable improvement evidence is incomplete.";
                if (!route.improvementReady()) checklist.add("For every claimed improvement, record a baseline, measurable target, and evaluation method.");
            } else if (predecessor != null && novelty >= 40 && distinctGapDocumented(input, predecessor)) {
                recommendation = Recommendation.NEW;
                explanation = "At least 40% of objectives are novel and the proposal documents a distinct context, beneficiary, or method.";
            } else {
                recommendation = Recommendation.REVIEW_REQUIRED;
                explanation = "The evidence does not yet satisfy a complete NEW, IMPROVE, CONTINUE, or duplicate-review gate.";
                checklist.add("Document the distinct gap or provide the structured predecessor evidence required by the intended route.");
            }
        }
        Double confidence = top == null ? null : top.confidence();
        AssessmentStatus confidenceState = top == null ? AssessmentStatus.UNASSESSED : status;
        DiscoveryRun run = new DiscoveryRun(UUID.randomUUID(), input.id(), status, recommendation,
                confidenceState, confidence,
                algorithmVersion, sha256(input.toString()), similarity.providerName(), explanation,
                List.copyOf(checklist), candidates, Instant.now());
        if (seeding) discoveries.put(run.id(), run);
        else afterCommit(() -> discoveries.put(run.id(), run));
        if (!seeding) store.saveDiscovery(run);
        if (!seeding) audit.append(actorEmail, "DISCOVERY_COMPLETED", "DISCOVERY_RUN", run.id(), "Completed explainable research discovery.",
                Map.of("status", status.name(), "recommendation", recommendation.name(), "algorithmVersion", algorithmVersion));
        return run;
    }

    @Transactional
    public DiscoveryRun runDiscovery(UUID proposalId) { return runDiscovery(proposal(proposalId)); }

    @Transactional
    public DiscoveryRun runDiscovery(UUID proposalId, String actorEmail) { return runDiscovery(proposal(proposalId), actorEmail); }

    @Transactional
    public synchronized ProposalDecision decide(UUID proposalId, UUID discoveryRunId, DecisionDisposition disposition,
                                                String rationale, UUID predecessorId) {
        return decide(proposalId, discoveryRunId, disposition, rationale, predecessorId, demoUser.email());
    }

    @Transactional
    public synchronized ProposalDecision decide(UUID proposalId, UUID discoveryRunId, DecisionDisposition disposition,
                                                String rationale, UUID predecessorId, String actorEmail) {
        proposal(proposalId);
        DiscoveryRun run = discovery(discoveryRunId);
        if (!run.proposalId().equals(proposalId)) throw new IllegalArgumentException("Discovery run does not belong to the proposal.");
        if (decisions.values().stream().anyMatch(existing -> existing.proposalId().equals(proposalId))) {
            throw new IllegalArgumentException("This proposal already has an immutable coordinator decision.");
        }
        if (Set.of(DecisionDisposition.APPROVE_CONTINUE, DecisionDisposition.APPROVE_IMPROVE,
                DecisionDisposition.CLOSE_AS_DUPLICATE).contains(disposition) && predecessorId == null) {
            throw new IllegalArgumentException("A candidate from the frozen discovery run is required for this disposition.");
        }
        if (predecessorId != null) {
            study(predecessorId);
            if (run.candidates().stream().noneMatch(candidate -> candidate.studyId().equals(predecessorId))) {
                throw new IllegalArgumentException("The selected candidate is not part of the frozen discovery run.");
            }
        }
        if (disposition == DecisionDisposition.APPROVE_CONTINUE
                && !routeAssessment(proposalId, predecessorId).continuationReady()) {
            throw new IllegalArgumentException("CONTINUE cannot be approved until at least 60% of objectives map to open work and code/data access is confirmed.");
        }
        if (disposition == DecisionDisposition.APPROVE_IMPROVE
                && !routeAssessment(proposalId, predecessorId).improvementReady()) {
            throw new IllegalArgumentException("IMPROVE cannot be approved until a limitation-based baseline, target, and evaluation method are recorded.");
        }
        ProposalDecision decision = new ProposalDecision(UUID.randomUUID(), proposalId, discoveryRunId, disposition,
                rationale, actorEmail, Instant.now(), predecessorId);
        decisions.put(decision.id(), decision);
        store.saveDecision(decision, actorEmail);
        if (approved(disposition)) createProjectFromDecision(decision, actorEmail);
        audit.append(actorEmail, "PROPOSAL_DECISION_RECORDED", "PROPOSAL", proposalId, "Recorded a human academic routing decision.",
                Map.of("disposition", disposition.name(), "decisionId", decision.id().toString()));
        return decision;
    }

    public RoutingEvidenceRepository.RouteAssessment routeAssessment(UUID proposalId, UUID predecessorId) {
        if (routingEvidence == null || predecessorId == null) {
            return new RoutingEvidenceRepository.RouteAssessment(AssessmentStatus.UNASSESSED, null, null, null,
                    AssessmentStatus.UNASSESSED, null);
        }
        return routingEvidence.assessment(proposalId, predecessorId);
    }

    @Transactional
    public synchronized Traceability rerunAnalysis(UUID projectId) {
        return rerunAnalysis(projectId, null);
    }

    @Transactional
    public synchronized Traceability rerunAnalysis(UUID projectId, String actorEmail) {
        Project project = project(projectId);
        Traceability current = traceability(projectId);
        Traceability updated = alignment.analyze(project, current.items(), current.links(), current.executions());
        traces.put(projectId, updated);
        ScopeRisk risk = alignment.scopeRisk(project, updated, 8, 12, List.of());
        scopeRisks.put(projectId, risk);
        ProjectHealth updatedHealth = alignment.health(project, updated, risk, packages.get(projectId));
        health.put(projectId, updatedHealth);
        store.saveTraceability(updated, actorEmail, "Recalculated the current traceability evidence.");
        store.saveScopeRisk(projectId, project.currentBaselineId(), risk, Instant.now());
        store.saveHealth(updatedHealth);
        audit.append(actorEmail, "ALIGNMENT_ANALYSIS_COMPLETED", "PROJECT", projectId, "Recalculated deterministic alignment findings and coverage.",
                Map.of("findingCount", updated.findings().size()));
        return updated;
    }

    @Transactional
    public synchronized AuthoringResult<TraceItem> createTraceItem(UUID projectId, String key, TraceItemType type,
            String title, String description, String priority, String acceptanceCriteria, String verificationMethod,
            String actorEmail) {
        Project current = editableProject(projectId);
        Traceability trace = traceability(projectId);
        String normalizedKey = key == null ? "" : key.strip().toUpperCase();
        if (!normalizedKey.matches("[A-Z][A-Z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Trace item key must contain 2 to 64 uppercase letters, digits, or hyphens.");
        }
        if (trace.items().stream().anyMatch(item -> item.key().equalsIgnoreCase(normalizedKey))) {
            throw new IllegalArgumentException("Trace item key already exists in this project: " + normalizedKey + ".");
        }
        validateTraceFields(type, priority);
        TraceItem created = new TraceItem(UUID.randomUUID(), normalizedKey, type, requiredText(title, "Trace item title"),
                requiredText(description, "Trace item description"), "DRAFT", normalizePriority(type, priority),
                blankToNull(acceptanceCriteria), blankToNull(verificationMethod), 1, 0);
        List<TraceItem> items = new ArrayList<>(trace.items());
        items.add(created);
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, items, trace.links(), trace.executions(), actorEmail);
        TraceItem persisted = item(analyzed, created.id());
        audit.append(actorEmail, "TRACE_ITEM_CREATED", "TRACE_ITEM", created.id(),
                "Created a draft trace item in the working evidence chain.", Map.of("projectId", projectId.toString(), "type", type.name()));
        return new AuthoringResult<>(touched, persisted, analyzed);
    }

    @Transactional
    public synchronized AuthoringResult<TraceItem> reviseTraceItem(UUID projectId, UUID itemId, String title,
            String description, String priority, String acceptanceCriteria, String verificationMethod, String actorEmail) {
        Project current = editableProject(projectId);
        Traceability trace = traceability(projectId);
        TraceItem existing = item(trace, itemId);
        validateTraceFields(existing.type(), priority);
        TraceItem revised = new TraceItem(existing.id(), existing.key(), existing.type(), requiredText(title, "Trace item title"),
                requiredText(description, "Trace item description"), "DRAFT", normalizePriority(existing.type(), priority),
                blankToNull(acceptanceCriteria), blankToNull(verificationMethod), existing.currentRevision() + 1, 0);
        List<TraceItem> items = trace.items().stream().map(value -> value.id().equals(itemId) ? revised : value).toList();
        Set<UUID> staleTests = testsInvalidatedBy(existing, trace);
        List<TestExecution> executions = trace.executions().stream().map(execution -> staleTests.contains(execution.testItemId())
                ? new TestExecution(execution.id(), execution.testItemId(), execution.status(), execution.buildIdentifier(), false,
                        execution.hasEvidence(), execution.executedAt())
                : execution).toList();
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, items, trace.links(), executions, actorEmail);
        TraceItem persisted = item(analyzed, itemId);
        audit.append(actorEmail, "TRACE_ITEM_REVISED", "TRACE_ITEM", itemId,
                "Created a new trace-item revision and invalidated dependent test evidence.",
                Map.of("projectId", projectId.toString(), "revision", revised.currentRevision(), "staleTests", staleTests.size()));
        return new AuthoringResult<>(touched, persisted, analyzed);
    }

    @Transactional
    public synchronized AuthoringResult<TraceLink> createTraceLink(UUID projectId, UUID sourceId, UUID targetId,
            String relationshipType, String rationale, String actorEmail) {
        Project current = editableProject(projectId);
        Traceability trace = traceability(projectId);
        TraceItem source = item(trace, sourceId);
        TraceItem target = item(trace, targetId);
        String type = relationshipType == null ? "" : relationshipType.strip().toUpperCase();
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("A trace item cannot link to itself.");
        if (!validRelationship(source.type(), target.type(), type)) {
            throw new IllegalArgumentException("Invalid typed trace direction: " + source.type() + " -[" + type + "]-> " + target.type() + ".");
        }
        if (trace.links().stream().anyMatch(link -> link.sourceId().equals(sourceId) && link.targetId().equals(targetId)
                && link.type().equalsIgnoreCase(type) && "ACTIVE".equals(link.status()))) {
            throw new IllegalArgumentException("That active trace relationship already exists.");
        }
        if (reachable(targetId, sourceId, trace.links())) throw new IllegalArgumentException("The trace relationship would create a cycle.");
        TraceLink created = new TraceLink(UUID.randomUUID(), sourceId, targetId, type, "ACTIVE",
                requiredText(rationale, "Trace link rationale"));
        List<TraceLink> links = new ArrayList<>(trace.links());
        links.add(created);
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, trace.items(), links, trace.executions(), actorEmail);
        audit.append(actorEmail, "TRACE_LINK_CREATED", "TRACE_LINK", created.id(),
                "Created a validated same-project typed trace relationship.", Map.of("projectId", projectId.toString(), "type", type));
        return new AuthoringResult<>(touched, created, analyzed);
    }

    @Transactional
    public synchronized AuthoringResult<TestExecution> recordTestExecution(UUID projectId, UUID testItemId,
            String status, String buildIdentifier, boolean evidenceConfirmed, String actorEmail) {
        Project current = editableProject(projectId);
        Traceability trace = traceability(projectId);
        TraceItem test = item(trace, testItemId);
        if (test.type() != TraceItemType.TEST_CASE || !approvedCurrent(test)) {
            throw new IllegalArgumentException("Executions may be recorded only for a current approved TEST_CASE item.");
        }
        String executionStatus = status == null ? "" : status.strip().toUpperCase();
        if (!Set.of("PASSED", "FAILED", "BLOCKED").contains(executionStatus)) {
            throw new IllegalArgumentException("Test execution status must be PASSED, FAILED, or BLOCKED.");
        }
        TestExecution created = new TestExecution(UUID.randomUUID(), testItemId, executionStatus,
                requiredText(buildIdentifier, "Build identifier"), true, evidenceConfirmed, Instant.now());
        List<TestExecution> executions = new ArrayList<>();
        for (TestExecution execution : trace.executions()) {
            executions.add(execution.testItemId().equals(testItemId)
                    ? new TestExecution(execution.id(), execution.testItemId(), execution.status(), execution.buildIdentifier(), false,
                            execution.hasEvidence(), execution.executedAt())
                    : execution);
        }
        executions.add(created);
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, trace.items(), trace.links(), executions, actorEmail);
        audit.append(actorEmail, "TEST_EXECUTION_RECORDED", "TEST_EXECUTION", created.id(),
                "Recorded baseline-bound test execution evidence.",
                Map.of("projectId", projectId.toString(), "status", executionStatus, "evidenceConfirmed", evidenceConfirmed));
        return new AuthoringResult<>(touched, created, analyzed);
    }

    @Transactional
    public synchronized BaselineApprovalResult approveBaseline(UUID projectId, String rationale, String actorEmail) {
        Project current = editableProject(projectId);
        Traceability working = traceability(projectId);
        List<TraceItem> promoted = working.items().stream()
                .filter(item -> !Set.of("REJECTED", "OBSOLETE").contains(item.lifecycleStatus()))
                .map(item -> new TraceItem(item.id(), item.key(), item.type(), item.title(), item.description(), "APPROVED",
                        item.priority(), item.acceptanceCriteria(), item.verificationMethod(), item.currentRevision(), item.readinessScore()))
                .toList();
        List<TraceLink> activeLinks = working.links().stream().filter(link -> "ACTIVE".equals(link.status())).toList();
        UUID baselineId = UUID.randomUUID();
        int baselineNumber = current.baselineNumber() + 1;
        Project candidateProject = new Project(current.id(), current.code(), current.title(), ProjectStatus.ACTIVE, current.route(),
                current.department(), baselineId, baselineNumber, current.team(), Instant.now(), current.rowVersion() + 1);
        Traceability candidate = alignment.analyze(candidateProject, promoted, activeLinks, working.executions());
        validateBaseline(candidate);
        projects.put(projectId, candidateProject);
        store.saveProject(candidateProject, required(projectProposalIds, projectId, "Project proposal"));
        traces.put(projectId, candidate);
        store.saveTraceability(candidate, actorEmail, requiredText(rationale, "Approval rationale"));
        ScopeRisk risk = alignment.scopeRisk(candidateProject, candidate, Math.min(20, promoted.size()), 0, List.of());
        scopeRisks.put(projectId, risk);
        store.saveScopeRisk(projectId, baselineId, risk, Instant.now());
        ProjectHealth updatedHealth = alignment.health(candidateProject, candidate, risk, packages.get(projectId));
        health.put(projectId, updatedHealth);
        store.saveHealth(updatedHealth);
        audit.append(actorEmail, "PROJECT_BASELINE_APPROVED", "PROJECT_BASELINE", baselineId,
                "Approved a new immutable traceability baseline after hard readiness and link validation.",
                Map.of("projectId", projectId.toString(), "baselineNumber", baselineNumber, "rationale", requiredText(rationale, "Approval rationale")));
        return new BaselineApprovalResult(candidateProject, candidate);
    }

    @Transactional
    public synchronized AuthoringResult<CompletionPackage> updateCompletionEvidence(UUID projectId,
            String repositoryUrl, String commitHash, String setupInstructions,
            List<String> limitations, List<String> recommendations, List<String> unfinishedWork,
            List<EvidenceReferenceInput> evidenceReferences, String actorEmail) {
        Project current = editableProject(projectId);
        CompletionPackage existing = completionPackage(projectId);
        List<JdbcWorkspaceStore.EvidenceReferenceRecord> incoming = normalizeEvidenceReferences(evidenceReferences);
        if (!incoming.isEmpty()) {
            store.saveEvidenceReferences("COMPLETION_PACKAGE", existing.id(), incoming, actorEmail, Instant.now());
        }
        List<JdbcWorkspaceStore.EvidenceReferenceRecord> persisted = store.evidenceReferences("COMPLETION_PACKAGE", existing.id());
        CompletionPackage updated = deriveCompletionPackage(current, existing, text(repositoryUrl), text(commitHash),
                text(setupInstructions), safeList(limitations), safeList(recommendations), safeList(unfinishedWork), persisted);
        packages.put(projectId, updated);
        store.saveCompletion(updated, actorEmail);
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, traceability(projectId).items(), traceability(projectId).links(),
                traceability(projectId).executions(), actorEmail);
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("projectId", projectId.toString());
        auditDetails.put("readinessState", updated.readinessState().name());
        if (updated.readinessScore() != null) auditDetails.put("readiness", updated.readinessScore());
        auditDetails.put("status", updated.status());
        audit.append(actorEmail, "COMPLETION_EVIDENCE_UPDATED", "COMPLETION_PACKAGE", existing.id(),
                "Updated structured completion and successor handoff evidence.", auditDetails);
        return new AuthoringResult<>(touched, updated, analyzed);
    }

    @Transactional
    public synchronized AuthoringResult<CompletionPackage> verifyCompletionReference(UUID projectId, UUID referenceId,
            String verificationState, String notes, String actorEmail) {
        Project current = editableProject(projectId);
        CompletionPackage existing = completionPackage(projectId);
        var verified = store.verifyEvidenceReference("COMPLETION_PACKAGE", existing.id(), referenceId,
                verificationState, requiredText(notes, "Verification notes"), actorEmail, Instant.now());
        List<JdbcWorkspaceStore.EvidenceReferenceRecord> references = store.evidenceReferences("COMPLETION_PACKAGE", existing.id());
        CompletionPackage updated = deriveCompletionPackage(current, existing, existing.repositoryUrl(), existing.commitHash(),
                existing.setupInstructions(), existing.limitations(), existing.recommendations(), existing.unfinishedWork(), references);
        packages.put(projectId, updated);
        store.saveCompletion(updated, actorEmail);
        Project touched = touchProject(current);
        Traceability analyzed = analyzeAndPersist(touched, traceability(projectId).items(), traceability(projectId).links(),
                traceability(projectId).executions(), actorEmail);
        audit.append(actorEmail, "COMPLETION_EVIDENCE_VERIFIED", "EVIDENCE_REFERENCE", referenceId,
                "Recorded an independent completion-evidence verification.",
                Map.of("projectId", projectId.toString(), "verificationState", verified.verificationState()));
        return new AuthoringResult<>(touched, updated, analyzed);
    }

    public List<JdbcWorkspaceStore.EvidenceReferenceRecord> completionEvidenceReferences(UUID projectId) {
        CompletionPackage pack = completionPackage(projectId);
        return store.evidenceReferences("COMPLETION_PACKAGE", pack.id());
    }

    private CompletionPackage deriveCompletionPackage(Project project, CompletionPackage existing, String repositoryUrl,
            String commitHash, String setupInstructions, List<String> limitations, List<String> recommendations,
            List<String> unfinishedWork, List<JdbcWorkspaceStore.EvidenceReferenceRecord> references) {
        Traceability trace = traceability(project.id());
        Instant now = Instant.now();
        Map<UUID, TraceItem> byId = trace.items().stream().collect(Collectors.toMap(TraceItem::id, value -> value));

        boolean tracePreserved = project.currentBaselineId() != null && project.currentBaselineId().equals(trace.baselineId())
                && !trace.items().isEmpty();
        ContinuityCriterion traceCriterion = tracePreserved
                ? assessedCriterion("trace", "Preserved trace and baseline history", 20, "APPROVED_BASELINE_AND_TRACE", now,
                        "The current immutable baseline and its trace records are persisted.")
                : unassessedCriterion("trace", "Preserved trace and baseline history", 20,
                        "No current persisted baseline is available for assessment.");

        List<TraceItem> objectives = trace.items().stream().filter(item -> item.type() == TraceItemType.OBJECTIVE && approvedCurrent(item)).toList();
        long objectivesWithOutputs = objectives.stream().filter(objective -> !AlignmentAnalyzer.approvedReachableTargets(
                objective.id(), trace.links(), byId, TraceItemType.OUTPUT).isEmpty()).count();
        double outputCoverage = objectives.isEmpty() ? 0 : (double) objectivesWithOutputs / objectives.size();
        boolean verifiedOutput = hasVerifiedReference(references, "OUTPUT", null);
        boolean anyOutputReference = hasReference(references, "OUTPUT", null);
        ContinuityCriterion outputCriterion;
        if (objectives.isEmpty() && !anyOutputReference) {
            outputCriterion = unassessedCriterion("outputs", "Final documents and outputs", 15,
                    "No approved objectives or output references are available for assessment.");
        } else if (outputCoverage == 1 && verifiedOutput) {
            outputCriterion = assessedCriterion("outputs", "Final documents and outputs", 15,
                    "TRACE_GRAPH_AND_VERIFIED_OUTPUT_REFERENCE", now,
                    "Every approved objective reaches an output and an independent output reference is verified.");
        } else {
            outputCriterion = partialCriterion("outputs", "Final documents and outputs", 15,
                    objectives.isEmpty() ? null : outputCoverage, "TRACE_GRAPH_AND_OUTPUT_REFERENCES", now,
                    verifiedOutput ? "Some approved objectives do not yet reach a final output."
                            : "Output trace paths require an independently verified output reference.");
        }

        int repositoryParts = (blank(repositoryUrl) ? 0 : 1) + (blank(commitHash) ? 0 : 1) + (blank(setupInstructions) ? 0 : 1);
        boolean verifiedRepository = hasVerifiedReference(references, "REPOSITORY", null);
        boolean anyRepository = hasReference(references, "REPOSITORY", null);
        ContinuityCriterion repositoryCriterion;
        if (repositoryParts == 0 && !anyRepository) {
            repositoryCriterion = unassessedCriterion("repository", "Repository, setup, licence, and access", 20,
                    "No repository handoff evidence is recorded.");
        } else if (repositoryParts == 3 && verifiedRepository) {
            repositoryCriterion = assessedCriterion("repository", "Repository, setup, licence, and access", 20,
                    "REPOSITORY_FIELDS_AND_VERIFIED_REFERENCE", now,
                    "Repository revision, setup instructions, and an independently verified reference are present.");
        } else {
            repositoryCriterion = partialCriterion("repository", "Repository, setup, licence, and access", 20,
                    repositoryParts == 0 ? null : repositoryParts / 3.0, "REPOSITORY_FIELDS_AND_REFERENCES", now,
                    verifiedRepository ? "Repository handoff fields remain incomplete."
                            : "The repository handoff requires independent verification.");
        }

        List<TraceItem> mandatoryTests = trace.items().stream().filter(item -> item.type() == TraceItemType.TEST_CASE
                && "MANDATORY".equals(item.priority()) && approvedCurrent(item)).toList();
        Map<UUID, TestExecution> latest = trace.executions().stream().collect(Collectors.toMap(TestExecution::testItemId,
                value -> value, (left, right) -> left.executedAt().isAfter(right.executedAt()) ? left : right));
        long passingTests = mandatoryTests.stream().filter(test -> {
            TestExecution execution = latest.get(test.id());
            return execution != null && execution.current() && "PASSED".equals(execution.status()) && execution.hasEvidence();
        }).count();
        double testCoverage = mandatoryTests.isEmpty() ? 0 : (double) passingTests / mandatoryTests.size();
        boolean verifiedTest = hasVerifiedReference(references, "TEST_RUN", null);
        boolean anyTestReference = hasReference(references, "TEST_RUN", null);
        ContinuityCriterion testsCriterion;
        if (mandatoryTests.isEmpty() && !anyTestReference) {
            testsCriterion = unassessedCriterion("tests", "Test and evidence snapshot", 15,
                    "No current mandatory tests or test-run references are available.");
        } else if (testCoverage == 1 && verifiedTest) {
            testsCriterion = assessedCriterion("tests", "Test and evidence snapshot", 15,
                    "CURRENT_TEST_EXECUTIONS_AND_VERIFIED_REFERENCE", now,
                    "All mandatory tests currently pass with evidence and the test-run reference is independently verified.");
        } else {
            testsCriterion = partialCriterion("tests", "Test and evidence snapshot", 15,
                    mandatoryTests.isEmpty() ? null : testCoverage, "CURRENT_TEST_EXECUTIONS_AND_REFERENCES", now,
                    verifiedTest ? "Some mandatory tests lack current passing evidence."
                            : "The current test snapshot requires an independently verified test-run reference.");
        }

        int futureParts = (limitations.isEmpty() ? 0 : 1) + (recommendations.isEmpty() ? 0 : 1)
                + (unfinishedWork.isEmpty() ? 0 : 1);
        ContinuityCriterion futureCriterion = futureParts == 0
                ? unassessedCriterion("future-work", "Limitations and unfinished work", 15,
                        "No structured limitations, recommendations, or unfinished work are recorded.")
                : futureParts == 3
                    ? assessedCriterion("future-work", "Limitations and unfinished work", 15,
                            "STRUCTURED_HANDOFF_ITEMS", now,
                            "Limitations, successor recommendations, and unfinished work are explicitly recorded.")
                    : partialCriterion("future-work", "Limitations and unfinished work", 15, futureParts / 3.0,
                            "STRUCTURED_HANDOFF_ITEMS", now,
                            "Record all three handoff categories: limitations, recommendations, and unfinished work.");

        java.util.function.Predicate<JdbcWorkspaceStore.EvidenceReferenceRecord> rightsPurpose = reference -> {
            String label = reference.label() == null ? "" : reference.label().toLowerCase();
            return label.matches(".*(rights|licen[cs]e|ownership|consent|data custodian|access approval).*" );
        };
        boolean verifiedRights = hasVerifiedReference(references, null, rightsPurpose);
        boolean anyRights = hasReference(references, null, rightsPurpose);
        ContinuityCriterion rightsCriterion = verifiedRights
                ? assessedCriterion("rights", "Ownership, data access, and contact path", 15,
                        "VERIFIED_RIGHTS_REFERENCE", now,
                        "An independent project member verified the recorded rights or access evidence.")
                : anyRights
                    ? partialCriterion("rights", "Ownership, data access, and contact path", 15, null,
                            "RIGHTS_REFERENCE", now, "Rights evidence is recorded but not independently verified.")
                    : unassessedCriterion("rights", "Ownership, data access, and contact path", 15,
                            "No rights, ownership, licence, consent, or access-approval reference is recorded.");

        List<ContinuityCriterion> criteria = List.of(traceCriterion, outputCriterion, repositoryCriterion,
                testsCriterion, futureCriterion, rightsCriterion);
        boolean ready = criteria.stream().allMatch(value -> value.state() == AssessmentStatus.ASSESSED
                && value.value() != null && value.value() == 1.0);
        boolean allNumeric = criteria.stream().allMatch(value -> value.value() != null);
        Double readiness = allNumeric
                ? criteria.stream().mapToDouble(value -> value.weight() * value.value()).sum()
                : null;
        AssessmentStatus readinessState = ready ? AssessmentStatus.ASSESSED
                : criteria.stream().allMatch(value -> value.state() == AssessmentStatus.UNASSESSED)
                    ? AssessmentStatus.UNASSESSED : AssessmentStatus.PARTIAL;
        List<String> blockers = criteria.stream().filter(value -> value.state() != AssessmentStatus.ASSESSED
                || value.value() == null || value.value() < 1).map(value -> value.explanation()).distinct().toList();
        return new CompletionPackage(existing.id(), project.id(), ready ? "READY" : "IN_PROGRESS", readinessState,
                readiness, verifiedRights, criteria, blockers, repositoryUrl, commitHash, setupInstructions,
                limitations, recommendations, unfinishedWork);
    }

    private static List<JdbcWorkspaceStore.EvidenceReferenceRecord> normalizeEvidenceReferences(List<EvidenceReferenceInput> inputs) {
        Set<String> allowed = Set.of("DOCUMENT", "URL", "REPOSITORY", "OUTPUT", "TEST_RUN", "DATASET", "OTHER");
        List<JdbcWorkspaceStore.EvidenceReferenceRecord> normalized = new ArrayList<>();
        for (EvidenceReferenceInput input : safeList(inputs)) {
            String type = input.type() == null ? "" : input.type().strip().toUpperCase();
            if (!allowed.contains(type)) throw new IllegalArgumentException("Unsupported completion evidence reference type: " + type + ".");
            String label = requiredText(input.label(), "Evidence reference label");
            String location = text(input.location());
            if (blank(location) && input.documentId() == null) {
                throw new IllegalArgumentException("An evidence reference requires a location or stored document ID.");
            }
            String sha = text(input.sha256()).toLowerCase();
            if (!blank(sha) && !sha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Evidence SHA-256 must contain exactly 64 hexadecimal characters.");
            }
            normalized.add(new JdbcWorkspaceStore.EvidenceReferenceRecord(UUID.randomUUID(), type, label,
                    blank(location) ? null : location, input.documentId(), blank(sha) ? null : sha,
                    "UNVERIFIED", Instant.now()));
        }
        return List.copyOf(normalized);
    }

    private static boolean hasVerifiedReference(List<JdbcWorkspaceStore.EvidenceReferenceRecord> references, String type,
            java.util.function.Predicate<JdbcWorkspaceStore.EvidenceReferenceRecord> purpose) {
        return safeList(references).stream().anyMatch(reference -> "VERIFIED".equals(reference.verificationState())
                && (type == null || type.equals(reference.type())) && (purpose == null || purpose.test(reference)));
    }

    private static boolean hasReference(List<JdbcWorkspaceStore.EvidenceReferenceRecord> references, String type,
            java.util.function.Predicate<JdbcWorkspaceStore.EvidenceReferenceRecord> purpose) {
        return safeList(references).stream().anyMatch(reference -> (type == null || type.equals(reference.type()))
                && (purpose == null || purpose.test(reference)));
    }

    private static ContinuityCriterion assessedCriterion(String key, String label, int weight, String source,
            Instant assessedAt, String explanation) {
        return new ContinuityCriterion(key, label, weight, AssessmentStatus.ASSESSED, 1.0, source, assessedAt, explanation);
    }

    private static ContinuityCriterion partialCriterion(String key, String label, int weight, Double value, String source,
            Instant assessedAt, String explanation) {
        return new ContinuityCriterion(key, label, weight, AssessmentStatus.PARTIAL, value, source, assessedAt, explanation);
    }

    private static ContinuityCriterion unassessedCriterion(String key, String label, int weight, String explanation) {
        return new ContinuityCriterion(key, label, weight, AssessmentStatus.UNASSESSED, null, null, null, explanation);
    }

    public record AuthoringResult<T>(Project project, T artifact, Traceability traceability) {}
    public record BaselineApprovalResult(Project project, Traceability baseline) {}
    public record EvidenceReferenceInput(String type, String label, String location, UUID documentId, String sha256) {}

    private Project editableProject(UUID projectId) {
        Project project = project(projectId);
        if (project.status() == ProjectStatus.COMPLETED) {
            throw new IllegalArgumentException("Completed projects are immutable; continue them through a successor project.");
        }
        return project;
    }

    private Project touchProject(Project project) {
        Project touched = new Project(project.id(), project.code(), project.title(), project.status(), project.route(),
                project.department(), project.currentBaselineId(), project.baselineNumber(), project.team(), Instant.now(),
                project.rowVersion() + 1);
        projects.put(project.id(), touched);
        store.saveProject(touched, required(projectProposalIds, project.id(), "Project proposal"));
        return touched;
    }

    @Transactional
    public Project recordGovernanceMutation(UUID projectId) {
        return touchProject(editableProject(projectId));
    }

    private Traceability analyzeAndPersist(Project project, List<TraceItem> items, List<TraceLink> links,
            List<TestExecution> executions, String actorEmail) {
        Traceability analyzed = alignment.analyze(project, List.copyOf(items), List.copyOf(links), List.copyOf(executions));
        traces.put(project.id(), analyzed);
        store.saveTraceability(analyzed, actorEmail, null);
        int unapproved = (int) analyzed.items().stream().filter(item -> "DRAFT".equals(item.lifecycleStatus())).count() * 5;
        ScopeRisk risk = alignment.scopeRisk(project, analyzed, Math.min(20, project.baselineNumber() * 4), unapproved, List.of());
        scopeRisks.put(project.id(), risk);
        store.saveScopeRisk(project.id(), project.currentBaselineId(), risk, Instant.now());
        ProjectHealth updatedHealth = alignment.health(project, analyzed, risk, packages.get(project.id()));
        health.put(project.id(), updatedHealth);
        store.saveHealth(updatedHealth);
        return analyzed;
    }

    private static TraceItem item(Traceability trace, UUID itemId) {
        return trace.items().stream().filter(item -> item.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Trace item does not belong to the selected project: " + itemId + "."));
    }

    private static void validateTraceFields(TraceItemType type, String priority) {
        if (type == null) throw new IllegalArgumentException("Trace item type is required.");
        if (priority == null || priority.isBlank()) return;
        String normalized = priority.strip().toUpperCase();
        if (type == TraceItemType.REQUIREMENT && !Set.of("MUST", "SHOULD", "COULD").contains(normalized)) {
            throw new IllegalArgumentException("Requirement priority must be MUST, SHOULD, or COULD.");
        }
        if (type == TraceItemType.TEST_CASE && !Set.of("MANDATORY", "OPTIONAL").contains(normalized)) {
            throw new IllegalArgumentException("Test priority must be MANDATORY or OPTIONAL.");
        }
        if (type != TraceItemType.REQUIREMENT && type != TraceItemType.TEST_CASE) {
            throw new IllegalArgumentException("Priority applies only to requirements and test cases.");
        }
    }

    private static String normalizePriority(TraceItemType type, String priority) {
        if (priority == null || priority.isBlank()) return null;
        return priority.strip().toUpperCase();
    }

    private static boolean validRelationship(TraceItemType source, TraceItemType target, String type) {
        return switch (source) {
            case PROBLEM -> target == TraceItemType.OBJECTIVE && type.equals("MOTIVATES");
            case OBJECTIVE -> target == TraceItemType.REQUIREMENT && type.equals("DECOMPOSES_TO");
            case REQUIREMENT -> target == TraceItemType.FEATURE && type.equals("REALIZED_BY")
                    || target == TraceItemType.TEST_CASE && type.equals("VERIFIED_BY");
            case FEATURE -> target == TraceItemType.TEST_CASE && type.equals("VERIFIED_BY")
                    || target == TraceItemType.OUTPUT && type.equals("CONTRIBUTES_TO");
            case TEST_CASE, OUTPUT -> false;
        };
    }

    private static boolean reachable(UUID start, UUID wanted, List<TraceLink> links) {
        Map<UUID, List<UUID>> outgoing = links.stream().filter(link -> "ACTIVE".equals(link.status()))
                .collect(Collectors.groupingBy(TraceLink::sourceId, Collectors.mapping(TraceLink::targetId, Collectors.toList())));
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(wanted)) return true;
            queue.addAll(outgoing.getOrDefault(current, List.of()));
        }
        return false;
    }

    private static Set<UUID> testsInvalidatedBy(TraceItem changed, Traceability trace) {
        if (changed.type() == TraceItemType.TEST_CASE) return Set.of(changed.id());
        if (changed.type() != TraceItemType.REQUIREMENT && changed.type() != TraceItemType.FEATURE) return Set.of();
        Map<UUID, TraceItem> items = trace.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        Map<UUID, List<UUID>> outgoing = trace.links().stream().filter(link -> "ACTIVE".equals(link.status()))
                .collect(Collectors.groupingBy(TraceLink::sourceId, Collectors.mapping(TraceLink::targetId, Collectors.toList())));
        Set<UUID> visited = new HashSet<>();
        Set<UUID> tests = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(changed.id());
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (UUID target : outgoing.getOrDefault(current, List.of())) {
                TraceItem item = items.get(target);
                if (item != null && item.type() == TraceItemType.TEST_CASE) tests.add(target);
                queue.add(target);
            }
        }
        return Set.copyOf(tests);
    }

    private static void validateBaseline(Traceability candidate) {
        Map<UUID, TraceItem> items = candidate.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        for (TraceLink link : candidate.links()) {
            TraceItem source = items.get(link.sourceId());
            TraceItem target = items.get(link.targetId());
            if (source == null || target == null || !validRelationship(source.type(), target.type(), link.type().toUpperCase())) {
                throw new IllegalArgumentException("Baseline contains an invalid or cross-project trace relationship.");
            }
        }
        for (TraceItem item : candidate.items()) {
            boolean outgoing;
            boolean incoming;
            switch (item.type()) {
                case PROBLEM -> {
                    outgoing = hasLink(candidate.links(), item.id(), null, "MOTIVATES", items, TraceItemType.OBJECTIVE);
                    if (!outgoing) throw new IllegalArgumentException("Every baseline problem must motivate an objective.");
                }
                case OBJECTIVE -> {
                    outgoing = hasLink(candidate.links(), item.id(), null, "DECOMPOSES_TO", items, TraceItemType.REQUIREMENT);
                    if (!outgoing) throw new IllegalArgumentException("Every baseline objective must decompose to a requirement.");
                    if (AlignmentAnalyzer.approvedReachableTargets(item.id(), candidate.links(), items, TraceItemType.OUTPUT).isEmpty()) {
                        throw new IllegalArgumentException("Every baseline objective must have a valid path to a final output.");
                    }
                }
                case REQUIREMENT -> {
                    incoming = hasLink(candidate.links(), null, item.id(), "DECOMPOSES_TO", items, TraceItemType.OBJECTIVE);
                    boolean feature = hasLink(candidate.links(), item.id(), null, "REALIZED_BY", items, TraceItemType.FEATURE);
                    boolean test = hasLink(candidate.links(), item.id(), null, "VERIFIED_BY", items, TraceItemType.TEST_CASE);
                    if (!incoming || !feature || !test || item.readinessScore() < 85 || item.acceptanceCriteria() == null
                            || item.verificationMethod() == null || item.priority() == null) {
                        throw new IllegalArgumentException("Every requirement must be 85+ ready and linked to an objective, feature, and test before baseline approval.");
                    }
                }
                case FEATURE -> {
                    incoming = hasLink(candidate.links(), null, item.id(), "REALIZED_BY", items, TraceItemType.REQUIREMENT);
                    if (!incoming) throw new IllegalArgumentException("Every baseline feature must be justified by a requirement.");
                }
                case TEST_CASE -> {
                    incoming = hasLink(candidate.links(), null, item.id(), "VERIFIED_BY", items, TraceItemType.REQUIREMENT)
                            || hasLink(candidate.links(), null, item.id(), "VERIFIED_BY", items, TraceItemType.FEATURE);
                    if (!incoming) throw new IllegalArgumentException("Every baseline test must target a requirement or feature.");
                }
                case OUTPUT -> { /* objective-path validation is performed from every objective and by analysis. */ }
            }
        }
        boolean blockingFinding = candidate.findings().stream().anyMatch(finding -> finding.state().name().equals("OPEN")
                && finding.severity().ordinal() >= Severity.HIGH.ordinal() && !finding.code().equals("STALE_TEST_EVIDENCE"));
        if (blockingFinding) throw new IllegalArgumentException("Resolve high-severity alignment findings before baseline approval.");
    }

    private static boolean hasLink(List<TraceLink> links, UUID sourceId, UUID targetId, String type,
            Map<UUID, TraceItem> items, TraceItemType otherType) {
        return links.stream().filter(link -> "ACTIVE".equals(link.status()) && link.type().equalsIgnoreCase(type))
                .filter(link -> sourceId == null || link.sourceId().equals(sourceId))
                .filter(link -> targetId == null || link.targetId().equals(targetId))
                .anyMatch(link -> {
                    UUID other = sourceId == null ? link.sourceId() : link.targetId();
                    TraceItem item = items.get(other);
                    return item != null && item.type() == otherType;
                });
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String text(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? "" : normalized;
    }

    @Transactional
    public synchronized ChangeRequest createChange(UUID projectId, UUID baselineId, String title, String rationale,
                                                   List<UUID> changedItems, List<String> boundaryFlags) {
        return createChange(projectId, baselineId, title, rationale, changedItems, boundaryFlags, null);
    }

    @Transactional
    public synchronized ChangeRequest createChange(UUID projectId, UUID baselineId, String title, String rationale,
                                                   List<UUID> changedItems, List<String> boundaryFlags, String actorEmail) {
        Project project = project(projectId);
        if (baselineId == null) {
            throw new IllegalArgumentException("A concrete current baseline is required for a change request.");
        }
        if (project.currentBaselineId() == null || !project.currentBaselineId().equals(baselineId)) {
            throw new IllegalArgumentException("The change request must select the project's exact current baseline.");
        }
        Set<UUID> projectItems = traceability(projectId).items().stream().map(TraceItem::id).collect(Collectors.toSet());
        if (changedItems == null || changedItems.isEmpty() || changedItems.stream().anyMatch(item -> !projectItems.contains(item))) {
            throw new IllegalArgumentException("Every changed artifact must belong to the selected project trace baseline.");
        }
        ChangeRequest change = new ChangeRequest(UUID.randomUUID(), projectId, baselineId, title, rationale,
                "IMPACT_REVIEW", changedItems.stream().distinct().toList(), safeList(boundaryFlags).stream().distinct().toList(), Instant.now(), 0);
        changes.put(change.id(), change);
        store.saveChange(change, actorEmail);
        audit.append(actorEmail, "CHANGE_REQUEST_CREATED", "CHANGE_REQUEST", change.id(), "Created a baseline-bound change request.",
                Map.of("projectId", projectId.toString(), "baselineId", String.valueOf(baselineId)));
        previewImpact(change.id(), actorEmail);
        return change;
    }

    @Transactional
    public synchronized ImpactPreview previewImpact(UUID changeId) {
        return previewImpact(changeId, null);
    }

    @Transactional
    public synchronized ImpactPreview previewImpact(UUID changeId, String actorEmail) {
        ChangeRequest change = change(changeId);
        Project project = project(change.projectId());
        Traceability trace = traceability(project.id());
        int unapproved = change.changedItemIds().size() * 5;
        ScopeRisk risk = alignment.scopeRisk(project, trace, 8, unapproved, change.boundaryFlags());
        ImpactPreview calculated = impactAnalyzer.preview(change, project, trace.items(), trace.links(), risk);
        long operationVersion = store.operationSetVersion(changeId);
        String operationDigest = store.operationSetDigest(changeId);
        ImpactPreview preview = new ImpactPreview(calculated.changeRequestId(), calculated.basedOnBaselineId(),
                calculated.baselineCurrent(), calculated.scopeRisk(), calculated.impactedArtifacts(),
                calculated.documentsToRevise(), calculated.calculatedAt(), operationVersion, operationDigest);
        impacts.put(change.id(), preview);
        if (!seeding) store.saveImpact(preview);
        if (!seeding) audit.append(actorEmail, "CHANGE_IMPACT_CALCULATED", "CHANGE_REQUEST", change.id(), "Calculated cycle-safe impact paths.",
                Map.of("baselineCurrent", preview.baselineCurrent(), "impactedArtifacts", preview.impactedArtifacts().size()));
        return preview;
    }

    @Transactional
    public synchronized BaselineApprovalResult decideChange(UUID changeId, ChangeDecisionDisposition disposition,
            List<ChangeOperation> operations, String rationale, String actorEmail) {
        ChangeRequest request = change(changeId);
        Project current = editableProject(request.projectId());
        if (Set.of("APPROVED", "REJECTED").contains(request.status())) {
            throw new IllegalArgumentException("This change request already has a final decision.");
        }
        requiredText(rationale, "Change decision rationale");
        if (disposition != ChangeDecisionDisposition.APPROVE) {
            String status = disposition == ChangeDecisionDisposition.REJECT ? "REJECTED" : "RETURNED_FOR_REVISION";
            ChangeRequest updated = new ChangeRequest(request.id(), request.projectId(), request.basedOnBaselineId(),
                    request.title(), request.rationale(), status, request.changedItemIds(), request.boundaryFlags(),
                    request.createdAt(), request.rowVersion() + 1);
            changes.put(changeId, updated);
            store.saveChange(updated, actorEmail);
            Project touched = touchProject(current);
            audit.append(actorEmail, "CHANGE_" + disposition.name(), "CHANGE_REQUEST", changeId,
                    "Recorded a reviewed change disposition without altering the approved baseline.", Map.of("rationale", rationale));
            return new BaselineApprovalResult(touched, traceability(current.id()));
        }
        ImpactPreview preview = impact(changeId);
        long operationVersion = store.operationSetVersion(changeId);
        String operationDigest = store.operationSetDigest(changeId);
        if (preview.operationSetVersion() != operationVersion || !java.util.Objects.equals(preview.operationSetSha256(), operationDigest)) {
            throw new IllegalArgumentException("The typed operation set changed after impact analysis; calculate a new preview before approval.");
        }
        if (!preview.baselineCurrent() || !request.basedOnBaselineId().equals(current.currentBaselineId())) {
            throw new IllegalArgumentException("The request is based on a stale baseline; recalculate impact before approval.");
        }
        if (operations == null || operations.isEmpty()) throw new IllegalArgumentException("Approved changes require at least one typed operation.");

        Traceability existing = traceability(current.id());
        List<TraceItem> items = new ArrayList<>(existing.items());
        List<TraceLink> links = new ArrayList<>(existing.links());
        for (ChangeOperation operation : operations.stream().sorted(Comparator.comparingInt(ChangeOperation::order)).toList()) {
            switch (operation.type()) {
                case ADD -> {
                    validateTraceFields(operation.itemType(), operation.priority());
                    if (operation.itemKey() == null || operation.itemKey().isBlank() || operation.title() == null || operation.description() == null) {
                        throw new IllegalArgumentException("ADD operations require key, type, title, and description.");
                    }
                    if (items.stream().anyMatch(item -> item.key().equalsIgnoreCase(operation.itemKey()))) {
                        throw new IllegalArgumentException("ADD operation uses an existing trace key: " + operation.itemKey() + ".");
                    }
                    items.add(new TraceItem(UUID.randomUUID(), operation.itemKey().strip().toUpperCase(), operation.itemType(),
                            requiredText(operation.title(), "Added item title"), requiredText(operation.description(), "Added item description"),
                            "DRAFT", normalizePriority(operation.itemType(), operation.priority()), blankToNull(operation.acceptanceCriteria()),
                            blankToNull(operation.verificationMethod()), 1, 0));
                }
                case REVISE -> {
                    TraceItem old = item(new Traceability(existing.projectId(), existing.baselineId(), existing.baselineNumber(),
                            existing.assessmentStatus(), items, links, existing.executions(), existing.findings(), existing.coverage()), operation.targetItemId());
                    validateTraceFields(old.type(), operation.priority() == null ? old.priority() : operation.priority());
                    TraceItem revised = new TraceItem(old.id(), old.key(), old.type(),
                            operation.title() == null ? old.title() : requiredText(operation.title(), "Revised title"),
                            operation.description() == null ? old.description() : requiredText(operation.description(), "Revised description"),
                            "DRAFT", operation.priority() == null ? old.priority() : normalizePriority(old.type(), operation.priority()),
                            operation.acceptanceCriteria() == null ? old.acceptanceCriteria() : blankToNull(operation.acceptanceCriteria()),
                            operation.verificationMethod() == null ? old.verificationMethod() : blankToNull(operation.verificationMethod()),
                            old.currentRevision() + 1, 0);
                    items = new ArrayList<>(items.stream().map(value -> value.id().equals(old.id()) ? revised : value).toList());
                }
                case RETIRE -> {
                    TraceItem old = item(new Traceability(existing.projectId(), existing.baselineId(), existing.baselineNumber(),
                            existing.assessmentStatus(), items, links, existing.executions(), existing.findings(), existing.coverage()), operation.targetItemId());
                    TraceItem retired = new TraceItem(old.id(), old.key(), old.type(), old.title(), old.description(), "OBSOLETE",
                            old.priority(), old.acceptanceCriteria(), old.verificationMethod(), old.currentRevision() + 1, old.readinessScore());
                    items = new ArrayList<>(items.stream().map(value -> value.id().equals(old.id()) ? retired : value).toList());
                    links = new ArrayList<>(links.stream().map(link -> link.sourceId().equals(old.id()) || link.targetId().equals(old.id())
                            ? new TraceLink(link.id(), link.sourceId(), link.targetId(), link.type(), "RETIRED", link.rationale()) : link).toList());
                }
                case RELINK -> {
                    if (operation.sourceItemId() == null || operation.linkTargetItemId() == null || operation.relationshipType() == null) {
                        throw new IllegalArgumentException("RELINK requires source, target, and relationship type.");
                    }
                    TraceItem source = items.stream().filter(value -> value.id().equals(operation.sourceItemId())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("RELINK source is outside this project."));
                    TraceItem target = items.stream().filter(value -> value.id().equals(operation.linkTargetItemId())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("RELINK target is outside this project."));
                    String relation = operation.relationshipType().strip().toUpperCase();
                    if (!validRelationship(source.type(), target.type(), relation)) throw new IllegalArgumentException("RELINK has an invalid typed direction.");
                    if (operation.removeRelationship()) {
                        boolean matched = links.stream().anyMatch(link -> link.sourceId().equals(source.id()) && link.targetId().equals(target.id())
                                && link.type().equals(relation) && "ACTIVE".equals(link.status()));
                        if (!matched) throw new IllegalArgumentException("The active relationship to retire was not found.");
                        links = new ArrayList<>(links.stream().map(link -> link.sourceId().equals(source.id()) && link.targetId().equals(target.id()) && link.type().equals(relation)
                                ? new TraceLink(link.id(), link.sourceId(), link.targetId(), link.type(), "RETIRED", link.rationale()) : link).toList());
                    } else {
                        if (reachable(target.id(), source.id(), links)) throw new IllegalArgumentException("RELINK would create a trace cycle.");
                        links.add(new TraceLink(UUID.randomUUID(), source.id(), target.id(), relation, "ACTIVE", requiredText(operation.rationale(), "Link rationale")));
                    }
                }
            }
        }
        List<TraceItem> promoted = items.stream().filter(value -> !Set.of("REJECTED", "OBSOLETE").contains(value.lifecycleStatus()))
                .map(value -> new TraceItem(value.id(), value.key(), value.type(), value.title(), value.description(), "APPROVED",
                        value.priority(), value.acceptanceCriteria(), value.verificationMethod(), value.currentRevision(), value.readinessScore())).toList();
        List<TraceLink> active = links.stream().filter(link -> "ACTIVE".equals(link.status())).toList();
        List<TestExecution> staleEvidence = existing.executions().stream().map(execution -> new TestExecution(execution.id(),
                execution.testItemId(), execution.status(), execution.buildIdentifier(), false, execution.hasEvidence(), execution.executedAt())).toList();
        UUID baselineId = UUID.randomUUID();
        Project approved = new Project(current.id(), current.code(), current.title(), ProjectStatus.ACTIVE, current.route(),
                current.department(), baselineId, current.baselineNumber() + 1, current.team(), Instant.now(), current.rowVersion() + 1);
        Traceability baseline = alignment.analyze(approved, promoted, active, staleEvidence);
        validateBaseline(baseline);
        projects.put(current.id(), approved);
        store.saveProject(approved, required(projectProposalIds, current.id(), "Project proposal"));
        traces.put(current.id(), baseline);
        store.saveTraceability(baseline, actorEmail, requiredText(rationale, "Change approval rationale"));
        ChangeRequest changed = new ChangeRequest(request.id(), request.projectId(), request.basedOnBaselineId(), request.title(),
                request.rationale(), "APPROVED", request.changedItemIds(), request.boundaryFlags(), request.createdAt(), request.rowVersion() + 1);
        changes.put(changeId, changed);
        store.saveChange(changed, actorEmail);
        ScopeRisk risk = alignment.scopeRisk(approved, baseline, Math.min(20, promoted.size()), 0, request.boundaryFlags());
        scopeRisks.put(current.id(), risk);
        store.saveScopeRisk(current.id(), baselineId, risk, Instant.now());
        ProjectHealth updatedHealth = alignment.health(approved, baseline, risk, packages.get(current.id()));
        health.put(current.id(), updatedHealth);
        store.saveHealth(updatedHealth);
        audit.append(actorEmail, "CHANGE_APPROVED", "CHANGE_REQUEST", changeId,
                "Applied typed operations, invalidated prior evidence, and created one immutable baseline.",
                Map.of("baselineId", baselineId.toString(), "baselineNumber", approved.baselineNumber(), "operationCount", operations.size()));
        return new BaselineApprovalResult(approved, baseline);
    }

    @Transactional
    public synchronized Map<String, Object> completionAssessment(UUID projectId) {
        return completionAssessment(projectId, null);
    }

    @Transactional
    public synchronized Map<String, Object> completionAssessment(UUID projectId, String actorEmail) {
        return completionAssessment(projectId, actorEmail, Set.of());
    }

    @Transactional
    public synchronized Map<String, Object> completionAssessment(UUID projectId, String actorEmail, Set<UUID> nonBlockingFindingIds) {
        Project project = project(projectId);
        Traceability trace = traceability(projectId);
        CompletionPackage pack = completionPackage(projectId);
        List<String> blockers = new ArrayList<>(pack.blockers());
        Map<UUID, TraceItem> byId = trace.items().stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        Map<UUID, TestExecution> latest = trace.executions().stream().collect(Collectors.toMap(TestExecution::testItemId,
                execution -> execution, (left, right) -> left.executedAt().isAfter(right.executedAt()) ? left : right));
        List<TraceItem> mustRequirements = trace.items().stream().filter(item -> item.type() == TraceItemType.REQUIREMENT
                && "MUST".equals(item.priority()) && approvedCurrent(item)).toList();
        boolean allMustVerified = !mustRequirements.isEmpty() && mustRequirements.stream().allMatch(requirement -> {
            Set<UUID> linked = AlignmentAnalyzer.approvedReachableTargets(requirement.id(), trace.links(), byId, TraceItemType.TEST_CASE);
            Set<UUID> mandatory = linked.stream().filter(id -> "MANDATORY".equals(byId.get(id).priority())).collect(Collectors.toSet());
            return !mandatory.isEmpty() && mandatory.stream().allMatch(testId -> {
                TestExecution execution = latest.get(testId);
                return execution != null && execution.current() && "PASSED".equals(execution.status()) && execution.hasEvidence();
            });
        });
        if (!allMustVerified) blockers.add("Every Must requirement needs current, passing mandatory tests with attached evidence.");
        boolean everyObjectiveHasOutput = trace.items().stream()
                .filter(item -> item.type() == TraceItemType.OBJECTIVE && approvedCurrent(item))
                .allMatch(objective -> !AlignmentAnalyzer.approvedReachableTargets(
                        objective.id(), trace.links(), byId, TraceItemType.OUTPUT).isEmpty());
        if (!everyObjectiveHasOutput) blockers.add("Every approved objective must have a valid trace path to final output evidence.");
        if (trace.coverage().priorityWeightedPassingCoverage() < 90) blockers.add("Priority-weighted passing coverage must reach 90%.");
        if (!pack.codeDataRightsConfirmed()) blockers.add("Code and data access rights must be confirmed.");
        if (!("READY".equals(pack.status()) || "COMPLETE".equals(pack.status()))
                || pack.criteria().stream().anyMatch(criterion -> criterion.state() != AssessmentStatus.ASSESSED
                        || criterion.value() == null || criterion.value() < 1.0)
                || blank(pack.repositoryUrl()) || blank(pack.commitHash()) || blank(pack.setupInstructions())) {
            blockers.add("The continuity package must be complete, including repository revision, setup, and every readiness criterion.");
        }
        Set<UUID> dispositions = nonBlockingFindingIds == null ? Set.of() : nonBlockingFindingIds;
        if (trace.findings().stream().anyMatch(finding -> !dispositions.contains(finding.id())
                && finding.state().name().equals("OPEN") && finding.severity() == Severity.CRITICAL)) {
            blockers.add("Critical findings must be resolved or accepted by a coordinator.");
        }
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("projectId", project.id());
        assessment.put("eligible", blockers.isEmpty());
        assessment.put("blockers", List.copyOf(blockers));
        assessment.put("coverage", trace.coverage());
        assessment.put("continuity", pack);
        assessment.put("evaluatedAt", Instant.now());
        if (blockers.isEmpty()) {
            CompletionResult completed = completeProject(project, pack, trace, actorEmail);
            assessment.put("completedProject", completed.project());
            assessment.put("catalogueStudy", completed.study());
        }
        audit.append(actorEmail, "COMPLETION_GATES_EVALUATED", "PROJECT", projectId, "Evaluated completion gates without bypassing blockers.",
                Map.of("eligible", blockers.isEmpty(), "blockerCount", blockers.size()));
        return assessment;
    }

    private void persistDiscoveryInput(Proposal input, String actorEmail) {
        UUID problemId = id("discovery-problem-" + input.id());
        ProblemCase problem = new ProblemCase(problemId, input.title(), input.problemStatement(), input.stakeholder(),
                input.affectedUsers(), input.siteContext(), input.desiredOutcome(), input.constraints(), input.privacyClassification(),
                "DISCOVERY_ONLY", 0, input.submittedAt(), 0);
        problems.put(problemId, problem);
        proposals.put(input.id(), input);
        proposalProblemIds.put(input.id(), problemId);
        store.saveProblem(problem, actorEmail, false);
        store.saveProposal(input, problemId, actorEmail);
    }

    private static boolean approved(DecisionDisposition disposition) {
        return disposition == DecisionDisposition.APPROVE_NEW
                || disposition == DecisionDisposition.APPROVE_IMPROVE
                || disposition == DecisionDisposition.APPROVE_CONTINUE;
    }

    private void createProjectFromDecision(ProposalDecision decision, String actorEmail) {
        if (projectProposalIds.containsValue(decision.proposalId())) return;
        Proposal source = proposal(decision.proposalId());
        UUID projectId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        Recommendation route = switch (decision.disposition()) {
            case APPROVE_NEW -> Recommendation.NEW;
            case APPROVE_IMPROVE -> Recommendation.IMPROVE;
            case APPROVE_CONTINUE -> Recommendation.CONTINUE;
            default -> throw new IllegalArgumentException("Only approved academic routes create projects.");
        };
        Instant now = Instant.now();
        String code = "UGNAY-" + now.atZone(java.time.ZoneOffset.UTC).getYear() + "-" + projectId.toString().substring(0, 8).toUpperCase();
        Project project = new Project(projectId, code, source.title(), ProjectStatus.BASELINING, route,
                store.proposalDepartmentName(source.id()), baselineId, 1, List.of(), now, 0);
        projects.put(projectId, project);
        projectProposalIds.put(projectId, source.id());
        store.saveProject(project, source.id());
        store.ensureInitialProjectMemberships(projectId, source.id(), actorEmail);

        TraceItem problem = new TraceItem(UUID.randomUUID(), "P-01", TraceItemType.PROBLEM, source.title(),
                source.problemStatement(), "APPROVED", null, null, null, 1, 0);
        List<TraceItem> items = new ArrayList<>();
        List<TraceLink> links = new ArrayList<>();
        items.add(problem);
        for (int index = 0; index < source.objectives().size(); index++) {
            TraceItem objective = new TraceItem(UUID.randomUUID(), "O-" + String.format("%02d", index + 1),
                    TraceItemType.OBJECTIVE, source.objectives().get(index), source.objectives().get(index),
                    "APPROVED", null, null, null, 1, 0);
            items.add(objective);
            links.add(new TraceLink(UUID.randomUUID(), problem.id(), objective.id(), "MOTIVATES", "ACTIVE",
                    "Frozen from the approved proposal route."));
        }
        Traceability trace = alignment.analyze(project, items, links, List.of());
        traces.put(projectId, trace);
        store.saveTraceability(trace, actorEmail, "Initial baseline created from the approved academic route.");

        CompletionPackage completion = initialCompletion(projectId);
        packages.put(projectId, completion);
        store.saveCompletion(completion, actorEmail);
        ScopeRisk risk = new ScopeRisk(AssessmentStatus.UNASSESSED, null, null, null, null, null, null,
                List.of("Scope risk remains unassessed until an approved requirements baseline exists."));
        scopeRisks.put(projectId, risk);
        store.saveScopeRisk(projectId, baselineId, risk, now);
        ProjectHealth projectHealth = alignment.health(project, trace, risk, completion);
        health.put(projectId, projectHealth);
        store.saveHealth(projectHealth);

        List<LineageNode> nodes = new ArrayList<>();
        List<LineageEdge> edges = new ArrayList<>();
        if (decision.primaryPredecessorId() != null) {
            Study predecessor = study(decision.primaryPredecessorId());
            nodes.add(new LineageNode(predecessor.id(), "STUDY", predecessor.title(), predecessor.lifecycleStatus(),
                    predecessor.academicYear(), false));
            edges.add(new LineageEdge(UUID.randomUUID(), predecessor.id(), projectId,
                    route == Recommendation.CONTINUE ? LineageType.CONTINUES : LineageType.IMPROVES, true, decision.rationale()));
        }
        nodes.add(new LineageNode(projectId, "PROJECT", project.title(), project.status().name(),
                now.atZone(java.time.ZoneOffset.UTC).getYear() + "-" + (now.atZone(java.time.ZoneOffset.UTC).getYear() + 1), true));
        Lineage lineage = new Lineage(projectId, List.copyOf(nodes), List.copyOf(edges));
        lineages.put(projectId, lineage);
        store.saveLineage(lineage);

        Proposal routed = new Proposal(source.id(), source.title(), source.problemStatement(), source.stakeholder(), source.affectedUsers(),
                source.siteContext(), source.desiredOutcome(), source.constraints(), source.privacyClassification(), source.objectives(),
                source.proposedSolution(), source.methodology(), source.dataSources(), source.technology(), source.intendedUsers(),
                "APPROVED_" + route.name(), source.submittedAt(), source.rowVersion() + 1);
        proposals.put(source.id(), routed);
        store.saveProposal(routed, proposalProblemIds.get(source.id()), actorEmail);
    }

    private CompletionPackage initialCompletion(UUID projectId) {
        return new CompletionPackage(UUID.randomUUID(), projectId, "IN_PROGRESS", AssessmentStatus.UNASSESSED, null, false,
                List.of(
                        unassessedCriterion("trace", "Preserved trace and baseline history", 20, "Trace preservation has not been assessed."),
                        unassessedCriterion("outputs", "Final documents and outputs", 15, "Final outputs have not been assessed."),
                        unassessedCriterion("repository", "Repository, setup, licence, and access", 20, "Repository handoff has not been assessed."),
                        unassessedCriterion("tests", "Test and evidence snapshot", 15, "Verification evidence has not been assessed."),
                        unassessedCriterion("future-work", "Limitations and unfinished work", 15, "Future-work evidence has not been assessed."),
                        unassessedCriterion("rights", "Ownership, data access, and contact path", 15, "Rights evidence has not been assessed.")),
                List.of("Complete the approved trace baseline.", "Record current passing evidence.", "Prepare the continuity package."),
                "", "", "", List.of(), List.of(), List.of());
    }

    private CompletionResult completeProject(Project project, CompletionPackage pack, Traceability trace, String actorEmail) {
        Optional<UUID> existingStudyId = store.completedStudyId(project.id());
        if (project.status() == ProjectStatus.COMPLETED && existingStudyId.isPresent()) {
            return new CompletionResult(project, study(existingStudyId.get()));
        }
        Instant now = Instant.now();
        Project completed = new Project(project.id(), project.code(), project.title(), ProjectStatus.COMPLETED, project.route(),
                project.department(), project.currentBaselineId(), project.baselineNumber(), project.team(), now, project.rowVersion() + 1);
        store.saveProject(completed, projectProposalIds.get(project.id()));
        CompletionPackage completedPackage = new CompletionPackage(pack.id(), pack.projectId(), "COMPLETE",
                AssessmentStatus.ASSESSED, pack.readinessScore(), true,
                pack.criteria(), List.of(), pack.repositoryUrl(), pack.commitHash(), pack.setupInstructions(), pack.limitations(),
                pack.recommendations(), pack.unfinishedWork());
        store.saveCompletion(completedPackage, actorEmail);

        Proposal proposal = proposal(projectProposalIds.get(project.id()));
        UUID studyId = id("completed-study-" + project.id());
        List<ContinuationItem> continuation = new ArrayList<>();
        appendContinuation(continuation, studyId, "LIMITATION", pack.limitations());
        appendContinuation(continuation, studyId, "RECOMMENDATION", pack.recommendations());
        appendContinuation(continuation, studyId, "UNFINISHED_WORK", pack.unfinishedWork());
        List<String> objectives = trace.items().stream().filter(item -> item.type() == TraceItemType.OBJECTIVE)
                .map(TraceItem::title).toList();
        List<String> features = trace.items().stream().filter(item -> item.type() == TraceItemType.FEATURE)
                .map(TraceItem::title).toList();
        int year = now.atZone(java.time.ZoneOffset.UTC).getYear();
        Study catalogueStudy = new Study(studyId, project.code() + "-STUDY", project.title(), year + "-" + (year + 1),
                project.department(), "COMPLETED", "CAMPUS", proposal.proposedSolution(), proposal.problemStatement(),
                objectives.isEmpty() ? proposal.objectives() : objectives, List.of("research continuity", project.route().name().toLowerCase()),
                proposal.methodology(), String.join(", ", features), proposal.dataSources(), proposal.technology(), proposal.intendedUsers(),
                proposal.stakeholder(), proposal.siteContext(), List.copyOf(continuation));
        UUID persistedId = store.saveCompletedStudy(catalogueStudy, project.id());
        Study persisted = persistedId.equals(studyId) ? catalogueStudy
                : store.studies().stream().filter(candidate -> candidate.id().equals(persistedId)).findFirst().orElseThrow();
        audit.append(actorEmail, "PROJECT_COMPLETED", "PROJECT", project.id(),
                "Completed the project and published exactly one linked continuity study.", Map.of("studyId", persisted.id().toString()));
        afterCommit(() -> {
            projects.put(project.id(), completed);
            packages.put(project.id(), completedPackage);
            studies.put(persisted.id(), persisted);
        });
        return new CompletionResult(completed, persisted);
    }

    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private static void appendContinuation(List<ContinuationItem> target, UUID studyId, String type, List<String> values) {
        for (int index = 0; index < safeList(values).size(); index++) {
            String description = values.get(index);
            target.add(new ContinuationItem(id("completion-item-" + studyId + "-" + type + "-" + index), studyId, type,
                    description.length() <= 240 ? description : description.substring(0, 239) + "…", description, "OPEN", false));
        }
    }

    private record CompletionResult(Project project, Study study) {}

    private boolean distinctGapDocumented(Proposal proposal, Study predecessor) {
        return similarity.fieldScore(proposal.siteContext(), predecessor.siteContext()) < 45
                || similarity.fieldScore(proposal.stakeholder(), predecessor.stakeholders()) < 45
                || similarity.fieldScore(proposal.methodology(), predecessor.methodology()) < 45
                || similarity.fieldScore(proposal.intendedUsers(), predecessor.intendedUsers()) < 45;
    }

    private static boolean approvedCurrent(TraceItem item) {
        return item.currentRevision() > 0 && "APPROVED".equals(item.lifecycleStatus());
    }

    public Map<String, Object> algorithmDisclosure() {
        return Map.of(
                "version", algorithmVersion,
                "assessmentStatus", similarity.semanticAvailable() ? AssessmentStatus.ASSESSED : AssessmentStatus.PARTIAL,
                "semanticProvider", similarity.providerName(),
                "semanticAvailability", similarity.providerExplanation(),
                "fieldFormula", "50% semantic cosine + 35% TF-IDF cosine + 15% controlled-concept Jaccard",
                "fallbackPolicy", "Unavailable components remain zero; weights are never silently rescaled.",
                "decisionBoundary", "Recommendations are explainable decision support. Only authorized people record academic decisions.");
    }

    public boolean wouldCreateLineageCycle(UUID projectId, UUID source, UUID target) {
        return lineageValidator.wouldCreateCycle(lineage(projectId).edges(), source, target);
    }

    private void clearWorkspace() {
        studies.clear();
        problems.clear();
        proposals.clear();
        discoveries.clear();
        decisions.clear();
        projects.clear();
        traces.clear();
        scopeRisks.clear();
        health.clear();
        changes.clear();
        impacts.clear();
        packages.clear();
        lineages.clear();
        proposalProblemIds.clear();
        projectProposalIds.clear();
        reviewQueue.clear();
    }

    private void loadWorkspace(JdbcWorkspaceStore.WorkspaceState state) {
        state.studies().forEach(value -> studies.put(value.id(), value));
        state.problems().forEach(value -> problems.put(value.id(), value));
        state.proposals().forEach(value -> proposals.put(value.id(), value));
        proposalProblemIds.putAll(state.proposalProblemIds());
        state.discoveryRuns().forEach(value -> discoveries.put(value.id(), value));
        state.decisions().forEach(value -> decisions.put(value.id(), value));
        state.projects().forEach(value -> projects.put(value.id(), value));
        projectProposalIds.putAll(state.projectProposalIds());
        state.traceability().forEach(value -> traces.put(value.projectId(), value));
        scopeRisks.putAll(state.scopeRisks());
        health.putAll(state.health());
        state.changeRequests().forEach(value -> changes.put(value.id(), value));
        impacts.putAll(state.impactPreviews());
        state.completionPackages().forEach(value -> packages.put(value.projectId(), value));
        lineages.putAll(state.lineages());
        reviewQueue.addAll(state.reviewQueue());
    }

    private void persistWorkspace() {
        studies.values().forEach(store::saveStudy);
        problems.values().forEach(store::saveProblem);
        proposals.values().forEach(value -> store.saveProposal(value, required(proposalProblemIds, value.id(), "Proposal problem")));
        discoveries.values().forEach(store::saveDiscovery);
        decisions.values().forEach(store::saveDecision);
        projects.values().forEach(value -> store.saveProject(value, required(projectProposalIds, value.id(), "Project proposal")));
        traces.values().forEach(store::saveTraceability);
        scopeRisks.forEach((projectId, risk) -> store.saveScopeRisk(projectId, projects.get(projectId).currentBaselineId(), risk, Instant.now()));
        packages.values().forEach(store::saveCompletion);
        health.values().forEach(store::saveHealth);
        changes.values().forEach(store::saveChange);
        impacts.values().forEach(store::saveImpact);
        lineages.values().forEach(store::saveLineage);
        store.replaceReviewQueue(reviewQueue);
    }

    private void seed() {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        Study flood = study("study-flood", "CICS-2024-018", "LIGTAS: Offline Household Flood Readiness Planner", "2024-2025",
                "An offline-first readiness system for households exposed to recurring floods.",
                "Barangay households lack a reliable way to assess flood readiness and preserve an actionable household plan when internet service fails.",
                List.of("Assess household flood preparedness", "Generate a device-only readiness plan", "Provide evidence-based preparedness guidance"),
                List.of("baha", "flood", "disaster preparedness", "offline", "household"),
                "Design science with household scenario validation", "Readiness assessment, offline plan, local results history",
                "Household survey responses", "JavaScript and offline web storage", "Households and barangay responders", "Barangay Poblacion",
                "COMPLETED", List.of(
                        continuation("flood-cont-1", "study-flood", "LIMITATION", "No evacuation-route validation", "Validate safe routes with barangay responders."),
                        continuation("flood-cont-2", "study-flood", "RECOMMENDATION", "Add campus-specific readiness evidence", "Adapt the evidence model for campus buildings.")));
        Study farm = study("study-farm", "CICS-2025-007", "AgriPresyo: Farmer Price and Sales Decision Journal", "2025-2026",
                "A decision-support journal that preserves price offers, costs, and actual sale outcomes.",
                "Small farmers cannot reliably compare offers or learn from prior sales because decision evidence is fragmented.",
                List.of("Compare net farm-gate offers", "Record farmer decisions", "Link partial sales to actual outcomes"),
                List.of("magsasaka", "farmer", "presyo", "market", "income"),
                "Iterative prototyping with farmer validation", "Offer comparison, decision journal, sale outcomes",
                "Market prices and farmer-entered costs", "Laravel and SQLite", "Farmers and agricultural coordinators", "Cotabato province",
                "COMPLETED", List.of(continuation("farm-cont-1", "study-farm", "UNFINISHED_WORK", "Seasonal comparison", "Evaluate decisions across multiple crop seasons.")));
        Study hospital = study("study-hospital", "CICS-2023-032", "USM Hospital Operations Information System", "2023-2024",
                "A hospital workflow system covering patient records, radiology reports, and inventory transactions.",
                "Clinical units need controlled access to patient information and auditable operational records.",
                List.of("Protect clinical records", "Manage radiology reports", "Preserve inventory transactions"),
                List.of("hospital", "patient", "radiology", "inventory", "audit"),
                "Incremental web system development", "Patient records, radiology PDF reports, inventory ledger",
                "Clinical and inventory records", "Laravel and relational database", "Hospital staff", "University hospital",
                "COMPLETED", List.of(continuation("hospital-cont-1", "study-hospital", "LIMITATION", "Limited test coverage", "Expand verification of authorization boundaries.")));
        Study waste = study("study-waste", "CICS-2022-011", "Campus Waste Segregation Monitoring", "2022-2023",
                "A monitoring prototype for waste collection and segregation points.",
                "Facilities staff lack consolidated data about waste-bin status and collection patterns.",
                List.of("Map segregation points", "Record collection observations", "Summarize waste trends"),
                List.of("basura", "waste", "campus", "monitoring"),
                "Survey and prototype evaluation", "Waste-point registry and trend reports", "Manual collection observations",
                "Web application", "Facilities personnel", "Main campus", "SUSPENDED",
                List.of(continuation("waste-cont-1", "study-waste", "UNFINISHED_WORK", "Sensor validation unfinished", "Validate fill-level sensor reliability.")));
        List.of(flood, farm, hospital, waste).forEach(study -> studies.put(study.id(), study));

        ProblemCase problem = new ProblemCase(id("problem-campus-flood"), "Disconnected campus flood-readiness evidence",
                "Students and building coordinators receive general flood advisories, but the campus has no evidence-linked way to assess building readiness, document gaps, and preserve plans for offline use.",
                "University Disaster Risk Reduction Office", "Students, faculty, guards, and building coordinators",
                "Three flood-prone campus buildings", "Produce a building-level readiness plan that remains usable during connectivity loss.",
                "Pilot must use approved non-sensitive building information.", "INTERNAL", "READY", 3,
                now.minus(18, ChronoUnit.DAYS), 2);
        problems.put(problem.id(), problem);
        Proposal proposal = new Proposal(id("proposal-campus-flood"), "Campus Flood Readiness Continuity System",
                problem.problemStatement(), problem.stakeholder(), problem.affectedUsers(), problem.siteContext(), problem.desiredOutcome(),
                problem.constraints(), problem.privacyClassification(),
                List.of("Assess flood readiness of selected campus buildings", "Generate offline building response plans", "Link readiness gaps to verified campus evidence"),
                "Adapt the prior household readiness approach for campus buildings with evidence-controlled plans and coordinator review.",
                "Design science, expert review, and scenario-based validation", "Building profiles, readiness responses, approved safety references",
                "Java web application, MySQL, offline browser cache", "Campus building coordinators and students", "UNDER_REVIEW",
                now.minus(14, ChronoUnit.DAYS), 4);
        proposals.put(proposal.id(), proposal);
        proposalProblemIds.put(proposal.id(), problem.id());
        DiscoveryRun run = runDiscovery(proposal);
        ProposalDecision decision = new ProposalDecision(id("decision-campus-flood"), proposal.id(), run.id(), DecisionDisposition.APPROVE_IMPROVE,
                "The campus context and measurable building-level validation justify an improvement route. The household study remains the primary predecessor.",
                "Dr. Amara Reyes", now.minus(12, ChronoUnit.DAYS), flood.id());
        decisions.put(decision.id(), decision);

        UUID projectId = id("project-campus-flood");
        UUID baselineId = id("baseline-campus-flood-v2");
        Project project = new Project(projectId, "UGNAY-26-014", "Campus Flood Readiness Continuity System",
                ProjectStatus.VALIDATING, Recommendation.IMPROVE, "College of Information and Computing Sciences",
                baselineId, 2, List.of("Mika Santos", "Paolo Dizon", "Dr. L. Cruz"), now.minus(2, ChronoUnit.HOURS), 7);
        projects.put(project.id(), project);
        projectProposalIds.put(project.id(), proposal.id());
        List<TraceItem> items = seedTraceItems();
        List<TraceLink> links = seedTraceLinks();
        List<TestExecution> executions = seedExecutions(now);
        Traceability trace = alignment.analyze(project, items, links, executions);
        traces.put(project.id(), trace);
        ScopeRisk risk = alignment.scopeRisk(project, trace, 8, 10, List.of("NEW_INTEGRATION"));
        scopeRisks.put(project.id(), risk);

        CompletionPackage completion = seedCompletion(project.id());
        packages.put(project.id(), completion);
        health.put(project.id(), alignment.health(project, trace, risk, completion));

        ChangeRequest change = new ChangeRequest(id("change-route-alert"), project.id(), baselineId,
                "Add SMS escalation for critical building gaps", "Coordinators need an escalation path when an assessment exposes an immediate safety gap.",
                "IMPACT_REVIEW", List.of(id("trace-requirement-alert")), List.of("NEW_INTEGRATION", "PERSONAL_CONTACT_DATA"),
                now.minus(20, ChronoUnit.HOURS), 1);
        changes.put(change.id(), change);
        impacts.put(change.id(), impactAnalyzer.preview(change, project, trace.items(), trace.links(),
                alignment.scopeRisk(project, trace, 8, 15, change.boundaryFlags())));

        Lineage lineage = new Lineage(project.id(), List.of(
                new LineageNode(flood.id(), "STUDY", flood.title(), "COMPLETED", flood.academicYear(), false),
                new LineageNode(project.id(), "PROJECT", project.title(), project.status().name(), "2026-2027", true),
                new LineageNode(id("successor-evac"), "PROPOSAL", "Campus Evacuation Route Validation", "DRAFT", "2027-2028", false)),
                List.of(
                        new LineageEdge(id("lineage-flood-project"), flood.id(), project.id(), LineageType.IMPROVES, true,
                                "Adapts the offline readiness evidence loop to campus buildings."),
                        new LineageEdge(id("lineage-project-evac"), project.id(), id("successor-evac"), LineageType.CONTINUES, true,
                                "Claims the unfinished route-validation recommendation.")));
        lineages.put(project.id(), lineage);

        reviewQueue.addAll(List.of(
                new ReviewQueueItem(id("review-change"), "CHANGE_IMPACT", change.title(), project.code(), Severity.HIGH, "COORDINATOR",
                        "New integration and contact-data boundaries require formal review.", now.plus(2, ChronoUnit.DAYS)),
                new ReviewQueueItem(id("review-finding"), "ALIGNMENT_FINDING", "Unjustified chatbot feature", project.code(), Severity.HIGH, "ADVISER",
                        "Feature CHAT-01 has no approved requirement.", now.plus(1, ChronoUnit.DAYS)),
                new ReviewQueueItem(id("review-discovery"), "DISCOVERY_REVIEW", proposal.title(), project.code(), Severity.MODERATE, "ADVISER",
                        "Discovery is PARTIAL while local semantic inference is unavailable.", now.plus(3, ChronoUnit.DAYS))));
    }

    private List<TraceItem> seedTraceItems() {
        return List.of(
                item("trace-problem", "P-01", TraceItemType.PROBLEM, "Campus flood readiness is not evidence-linked", "Building coordinators cannot preserve and act on readiness gaps during connectivity loss.", "APPROVED", null, null, null),
                item("trace-objective-assess", "O-01", TraceItemType.OBJECTIVE, "Assess building readiness", "Measure readiness of selected campus buildings against approved evidence.", "APPROVED", null, null, null),
                item("trace-objective-plan", "O-02", TraceItemType.OBJECTIVE, "Generate offline response plans", "Produce actionable plans available without network access.", "APPROVED", null, null, null),
                item("trace-requirement-assessment", "R-01", TraceItemType.REQUIREMENT, "Structured building assessment", "The system shall let an authorized user record building readiness data and show missing required evidence.", "APPROVED", "MUST", "Given a complete assessment, all unanswered mandatory indicators are identified by code.", "TEST"),
                item("trace-requirement-offline", "R-02", TraceItemType.REQUIREMENT, "Offline plan access", "The user shall quickly access a user-friendly plan and/or related guidance while offline.", "APPROVED", "MUST", "A previously saved plan opens within 2 seconds while network access is disabled.", "TEST"),
                item("trace-requirement-alert", "R-03", TraceItemType.REQUIREMENT, "Critical-gap escalation", "The system shall notify a building coordinator when critical safety evidence is missing.", "DRAFT", "MUST", null, null),
                item("trace-feature-assess", "F-01", TraceItemType.FEATURE, "Evidence-led assessment studio", "Guided building assessment with evidence citations.", "APPROVED", null, null, null),
                item("trace-feature-offline", "F-02", TraceItemType.FEATURE, "Offline plan vault", "Device-cached response plans with freshness status.", "APPROVED", null, null, null),
                item("trace-feature-alert", "F-03", TraceItemType.FEATURE, "Coordinator escalation", "In-application escalation preview for critical gaps.", "DRAFT", null, null, null),
                item("trace-feature-chat", "CHAT-01", TraceItemType.FEATURE, "Preparedness chatbot", "A conversational helper suggested after the approved baseline.", "DRAFT", null, null, null),
                item("trace-test-assess", "T-01", TraceItemType.TEST_CASE, "Assessment completeness test", "Verify missing mandatory indicators are listed exactly once.", "APPROVED", "MANDATORY", null, null),
                item("trace-test-offline", "T-02", TraceItemType.TEST_CASE, "Offline launch test", "Verify a saved response plan opens without network access.", "APPROVED", "MANDATORY", null, null),
                item("trace-test-alert", "T-03", TraceItemType.TEST_CASE, "Escalation authorization test", "Verify only authorized coordinators see personal contact data.", "DRAFT", "MANDATORY", null, null),
                item("trace-output-plan", "OUT-01", TraceItemType.OUTPUT, "Validated offline response plan", "Versioned building response plan and evidence summary.", "APPROVED", null, null, null),
                item("trace-output-report", "OUT-02", TraceItemType.OUTPUT, "Pilot validation report", "Scenario results, limitations, and continuation recommendations.", "DRAFT", null, null, null));
    }

    private List<TraceLink> seedTraceLinks() {
        return List.of(
                link("l-p-o1", "trace-problem", "trace-objective-assess", "MOTIVATES"),
                link("l-p-o2", "trace-problem", "trace-objective-plan", "MOTIVATES"),
                link("l-o1-r1", "trace-objective-assess", "trace-requirement-assessment", "DECOMPOSES_TO"),
                link("l-o1-r3", "trace-objective-assess", "trace-requirement-alert", "DECOMPOSES_TO"),
                link("l-o2-r2", "trace-objective-plan", "trace-requirement-offline", "DECOMPOSES_TO"),
                link("l-r1-f1", "trace-requirement-assessment", "trace-feature-assess", "REALIZED_BY"),
                link("l-r2-f2", "trace-requirement-offline", "trace-feature-offline", "REALIZED_BY"),
                link("l-r3-f3", "trace-requirement-alert", "trace-feature-alert", "REALIZED_BY"),
                link("l-r1-t1", "trace-requirement-assessment", "trace-test-assess", "VERIFIED_BY"),
                link("l-r2-t2", "trace-requirement-offline", "trace-test-offline", "VERIFIED_BY"),
                link("l-r3-t3", "trace-requirement-alert", "trace-test-alert", "VERIFIED_BY"),
                link("l-f1-out1", "trace-feature-assess", "trace-output-plan", "CONTRIBUTES_TO"),
                link("l-f2-out1", "trace-feature-offline", "trace-output-plan", "CONTRIBUTES_TO"),
                link("l-f3-out2", "trace-feature-alert", "trace-output-report", "CONTRIBUTES_TO"));
    }

    private List<TestExecution> seedExecutions(Instant now) {
        return List.of(
                new TestExecution(id("exec-assess"), id("trace-test-assess"), "PASSED", "build-2026.08.07.1", true, true, now.minus(1, ChronoUnit.DAYS)),
                new TestExecution(id("exec-offline"), id("trace-test-offline"), "PASSED", "build-2026.07.21.3", false, true, now.minus(18, ChronoUnit.DAYS)),
                new TestExecution(id("exec-alert"), id("trace-test-alert"), "FAILED", "build-2026.08.07.1", true, true, now.minus(1, ChronoUnit.DAYS)));
    }

    private CompletionPackage seedCompletion(UUID projectId) {
        List<ContinuityCriterion> criteria = List.of(
                unassessedCriterion("trace", "Preserved trace and baseline history", 20, "Run the server assessment against the current baseline."),
                unassessedCriterion("outputs", "Final documents and outputs", 15, "Output references are unassessed."),
                unassessedCriterion("repository", "Repository, setup, licence, and access", 20, "Repository evidence is unassessed."),
                unassessedCriterion("tests", "Test and evidence snapshot", 15, "Test-run evidence is unassessed."),
                unassessedCriterion("future-work", "Limitations and unfinished work", 15, "Future-work evidence is unassessed."),
                unassessedCriterion("rights", "Ownership, data access, and contact path", 15, "Rights evidence is unassessed."));
        return new CompletionPackage(id("completion-campus-flood"), projectId, "IN_PROGRESS", AssessmentStatus.UNASSESSED,
                null, false, criteria, List.of("Run the server-derived completion assessment and independently verify references."),
                "https://github.com/example/ugnay-campus-flood", "d7d1c6e", "Use Docker Compose; copy .env.example and run the verified seed profile.",
                List.of("Pilot covers three buildings only.", "SMS delivery is not yet approved."),
                List.of("Validate evacuation routes with the disaster-risk office.", "Compare readiness results across two semesters."),
                List.of("Complete data-custodian approval.", "Re-run offline evidence on the release build."));
    }

    private Study study(String key, String code, String title, String year, String abstractText, String problem,
                        List<String> objectives, List<String> keywords, String methodology, String features,
                        String dataSources, String technology, String intendedUsers, String site, String status,
                        List<com.ugnay.platform.shared.PlatformModels.ContinuationItem> continuation) {
        String visibility = "CICS-2023-032".equals(code) ? "RESTRICTED" : "CAMPUS";
        return new Study(id(key), code, title, year, "College of Information and Computing Sciences", status,
                visibility, abstractText, problem, objectives, keywords, methodology, features, dataSources, technology,
                intendedUsers, intendedUsers, site, continuation);
    }

    private com.ugnay.platform.shared.PlatformModels.ContinuationItem continuation(String key, String studyKey, String type, String title, String description) {
        return new com.ugnay.platform.shared.PlatformModels.ContinuationItem(id(key), id(studyKey), type, title, description, "OPEN", false);
    }

    private TraceItem item(String id, String key, TraceItemType type, String title, String description,
                           String status, String priority, String criteria, String method) {
        return new TraceItem(id(id), key, type, title, description, status, priority, criteria, method, 1, 0);
    }

    private TraceLink link(String id, String source, String target, String type) {
        return new TraceLink(id(id), id(source), id(target), type, "ACTIVE", "Seeded evidence relationship.");
    }

    private static List<String> intakeChecklist(Proposal proposal) {
        List<String> missing = new ArrayList<>();
        if (blank(proposal.title())) missing.add("Provide a proposal title.");
        if (blank(proposal.problemStatement())) missing.add("Describe the evidenced problem.");
        if (blank(proposal.stakeholder())) missing.add("Identify the problem owner or stakeholder.");
        if (blank(proposal.siteContext())) missing.add("Describe the implementation context or site.");
        if (blank(proposal.desiredOutcome())) missing.add("State the desired outcome.");
        if (proposal.objectives() == null || proposal.objectives().isEmpty()) missing.add("Provide at least one measurable objective.");
        if (blank(proposal.proposedSolution())) missing.add("Describe the proposed solution boundary.");
        return missing;
    }

    private static <T> T required(Map<UUID, T> source, UUID id, String label) {
        T value = source.get(id);
        if (value == null) throw new NoSuchElementException(label + " " + id + " was not found.");
        return value;
    }

    private static <T> List<T> safeList(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public static UUID id(String key) { return UUID.nameUUIDFromBytes(("ugnay:" + key).getBytes(StandardCharsets.UTF_8)); }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

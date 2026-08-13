package com.ugnay.platform.workspace;

import com.ugnay.platform.identity.JdbcIdentityService;
import com.ugnay.platform.identity.ProjectAccessService;
import com.ugnay.platform.identity.StudyVisibilityPolicy;
import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.CandidateEvidence;
import com.ugnay.platform.shared.PlatformModels.ComponentScore;
import com.ugnay.platform.shared.PlatformModels.LineageType;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.ChangeOperationType;
import com.ugnay.platform.shared.PlatformModels.ChangeDecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.ContinuationClaimOutcome;
import com.ugnay.platform.shared.PlatformModels.ProblemCase;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.ProposalDecision;
import com.ugnay.platform.shared.PlatformModels.Study;
import com.ugnay.platform.warehouse.WarehouseRefreshRequested;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.Principal;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

@RestController
@RequestMapping("/api/v1")
public class UgnayApiController {
    private final WorkspaceService workspace;
    private final UiWorkspaceMapper ui;
    private final JdbcIdentityService identities;
    private final JdbcAuditService audit;
    private final ProjectAccessService projectAccess;
    private final StudyVisibilityPolicy studyVisibility;
    private final WorkflowActionService actions;
    private final IntakeWorkflowService intakes;
    private final CatalogueMetadataRepository catalogueMetadata;
    private final ApplicationEventPublisher events;

    public UgnayApiController(WorkspaceService workspace, UiWorkspaceMapper ui,
                               JdbcIdentityService identities, JdbcAuditService audit,
                               ProjectAccessService projectAccess, WorkflowActionService actions,
                               IntakeWorkflowService intakes, ApplicationEventPublisher events, StudyVisibilityPolicy studyVisibility) {
        this.workspace = workspace;
        this.ui = ui;
        this.identities = identities;
        this.audit = audit;
        this.projectAccess = projectAccess;
        this.studyVisibility = studyVisibility;
        this.actions = actions;
        this.intakes = intakes;
        this.catalogueMetadata = ui.catalogueMetadata();
        this.events = events;
    }

    @GetMapping("/workspace")
    public ResponseEntity<Object> workspace(Authentication authentication, @RequestParam(required = false) UUID projectId) {
        if (projectId != null) projectAccess.requireAccess(authentication, projectId);
        int totalProjects = (int) workspace.projects().stream()
                .filter(project -> projectAccess.canAccess(authentication, project.id())).count();
        return ResponseEntity.ok().headers(pageHeaders(0, 50, totalProjects, Math.min(totalProjects, 50)))
                .body(ui.workspace(authentication, projectId));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("isAuthenticated()")
    public Object dashboard(Authentication authentication) {
        List<Project> visibleProjects = workspace.projects().stream()
                .filter(project -> projectAccess.canAccess(authentication, project.id())).toList();
        Set<UUID> visibleProjectIds = visibleProjects.stream().map(Project::id).collect(java.util.stream.Collectors.toSet());
        Set<String> visibleProjectCodes = visibleProjects.stream().map(Project::code).collect(java.util.stream.Collectors.toSet());
        List<Project> boundedProjects = visibleProjects.stream().limit(50).toList();
        var visibleHealth = boundedProjects.stream().map(project -> workspace.health(project.id())).toList();
        var visibleReviews = workspace.reviewQueue().stream()
                .filter(review -> visibleProjectCodes.contains(review.projectCode())).limit(50).toList();
        var visibleStudies = ui.studies(authentication);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("publishedStudies", visibleStudies.size());
        counts.put("activeProjects", (int) visibleProjects.stream()
                .filter(project -> !"COMPLETED".equals(project.status().name())).count());
        counts.put("openFindings", visibleProjectIds.stream().mapToInt(projectId -> (int) workspace.traceability(projectId)
                .findings().stream().filter(finding -> finding.state() == FindingState.OPEN).count()).sum());
        counts.put("pendingReviews", visibleReviews.size());
        return Map.of("counts", counts, "projectHealth", visibleHealth, "reviewQueue", visibleReviews,
                "recentStudies", visibleStudies.stream().limit(3).toList(), "activeProjects", boundedProjects,
                "pagination", Map.of("page", 0, "size", 50, "totalProjects", visibleProjects.size(),
                        "truncated", visibleProjects.size() > boundedProjects.size()));
    }

    @GetMapping("/studies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UiContracts.StudyView>> studies(Authentication authentication,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<UiContracts.StudyView> permitted = ui.studies(authentication);
        List<UiContracts.StudyView> filtered = permitted;
        if (q != null && !q.isBlank()) {
            String query = q.toLowerCase();
            filtered = permitted.stream().filter(study -> (study.title() + " " + study.abstractText() + " " + String.join(" ", study.keywords()))
                    .toLowerCase().contains(query)).toList();
        }
        return paged(filtered, page, size);
    }

    @GetMapping("/studies/{id}")
    @PreAuthorize("isAuthenticated()")
    public Study study(@PathVariable UUID id, Authentication authentication) {
        Study study = workspace.study(id);
        studyVisibility.requireVisible(authentication, study.visibility(), study.department());
        return study;
    }

    @PostMapping("/imports/studies")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Study importStudy(@Valid @RequestBody StudyImportRequest request, Principal principal) {
        Study study = workspace.importStudy(request.institutionalCode(), request.title(), request.academicYear(), request.abstractText(),
                request.problemStatement(), request.objectives(), request.keywords(), request.methodology(), request.features(),
                request.stakeholders(), request.siteContext(), request.department(), request.dataSources(), request.technology(),
                request.intendedUsers(), request.visibility(), request.lifecycleStatus(), principal.getName());
        catalogueMetadata.updatePublication(study.id(), request.program(), request.authors(), request.doi(), request.repositoryIdentifier());
        catalogueMetadata.recordReviewedEvidence(study.id(), new CatalogueMetadataRepository.ReviewedEvidence(
                request.academicYear(), request.department(), request.resultsText(), request.dataSources(), request.technology(),
                request.intendedUsers(), request.researchAreas(), request.visibility(), request.lifecycleStatus()),
                principal.getName(), "CURATOR_REVIEW");
        events.publishEvent(new WarehouseRefreshRequested(principal.getName(), WarehouseRefreshRequested.Trigger.CATALOGUE_PUBLICATION));
        return study;
    }

    @PostMapping("/imports/documents/jobs/{jobId}/publish-study")
    @PreAuthorize("hasRole('CURATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Study publishStudy(@PathVariable UUID jobId, @Valid @RequestBody PublishStudyRequest request, Principal principal) {
        UUID documentVersionId = catalogueMetadata.requirePublicationEligibleVersion(jobId);
        Study study = workspace.importStudy(request.institutionalCode(), request.title(), request.academicYear(),
                request.abstractText(), request.problemStatement(), request.objectives(), request.keywords(),
                request.methodology(), request.features(), request.stakeholders(), request.siteContext(), request.department(),
                request.dataSources(), request.technology(), request.intendedUsers(), request.visibility(),
                request.lifecycleStatus(), principal.getName());
        catalogueMetadata.updatePublication(study.id(), request.program(), request.authors(), request.doi(), request.repositoryIdentifier());
        catalogueMetadata.recordReviewedEvidence(study.id(), new CatalogueMetadataRepository.ReviewedEvidence(
                request.academicYear(), request.department(), request.resultsText(), request.dataSources(), request.technology(),
                request.intendedUsers(), request.researchAreas(), request.visibility(), request.lifecycleStatus()),
                principal.getName(), "DOCUMENT_PUBLICATION");
        catalogueMetadata.linkPublication(study.id(), documentVersionId, principal.getName());
        events.publishEvent(new WarehouseRefreshRequested(principal.getName(), WarehouseRefreshRequested.Trigger.CATALOGUE_PUBLICATION));
        audit.append(principal.getName(), "STUDY_PUBLISHED_FROM_DOCUMENT", "STUDY", study.id(),
                "Published curator-reviewed metadata and linked its immutable source document version.",
                Map.of("documentVersionId", documentVersionId.toString(), "extractionJobId", jobId.toString()));
        return study;
    }

    @GetMapping("/problems")
    @PreAuthorize("isAuthenticated()")
    public List<ProblemCase> problems(Authentication authentication) {
        return workspace.problems().stream().filter(problem -> projectAccess.canAccessProblem(authentication, problem.id())).toList();
    }

    @GetMapping("/problems/{id}")
    @PreAuthorize("isAuthenticated()")
    public ProblemCase problem(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireProblemAccess(authentication, id);
        return workspace.problem(id);
    }

    @PostMapping("/problems")
    public ResponseEntity<ProblemCase> createProblem(@Valid @RequestBody ProblemRequest request, Principal principal) {
        ProblemCase created = workspace.createProblem(request.title(), request.problemStatement(), request.stakeholder(),
                request.affectedUsers(), request.siteContext(), request.desiredOutcome(), request.constraints(),
                request.privacyClassification(), 0, principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/problems/" + created.id())).eTag(etag(created.rowVersion())).body(created);
    }

    @PostMapping("/intakes")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<IntakeResponse> submitIntake(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody IntakeRequest request, Authentication principal) {
        var command = new IntakeWorkflowService.IntakeCommand(
                new IntakeWorkflowService.ProblemInput(request.problem().title(), request.problem().problemStatement(),
                        request.problem().stakeholder(), request.problem().affectedUsers(), request.problem().siteContext(),
                        request.problem().desiredOutcome(), request.problem().constraints(), request.problem().privacyClassification()),
                new IntakeWorkflowService.ProposalInput(request.proposal().title(), request.proposal().objectives(),
                        request.proposal().proposedSolution(), request.proposal().methodology(), request.proposal().dataSources(),
                        request.proposal().technology(), request.proposal().intendedUsers()),
                request.evidenceReferences() == null ? List.of() : request.evidenceReferences().stream()
                        .map(reference -> new IntakeWorkflowService.EvidenceReferenceInput(reference.type(), reference.label(),
                                reference.location(), reference.storedDocumentId(), reference.sha256())).toList());
        var result = intakes.submit(idempotencyKey, command, principal.getName());
        IntakeResponse response = new IntakeResponse(result.idempotencyKey(), result.replayed(), result.problem(), result.proposal(),
                ui.discovery(result.discovery(), principal), result.evidenceReferences());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/proposals/" + result.proposal().id()))
                .eTag(etag(result.proposal().rowVersion())).body(response);
    }

    @GetMapping("/proposals")
    @PreAuthorize("isAuthenticated()")
    public List<Proposal> proposals(Authentication authentication) {
        return workspace.proposals().stream().filter(proposal -> projectAccess.canAccessProposal(authentication, proposal.id())).toList();
    }

    @GetMapping("/proposals/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Proposal> proposal(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireProposalAccess(authentication, id);
        Proposal value = workspace.proposal(id);
        return ResponseEntity.ok().eTag(etag(value.rowVersion())).body(value);
    }

    @PostMapping("/proposals")
    public ResponseEntity<Proposal> createProposal(@Valid @RequestBody ProposalRequest request, Authentication authentication) {
        projectAccess.requireProblemAccess(authentication, request.problemId());
        Proposal created = workspace.createProposal(request.problemId(), request.title(), request.objectives(),
                request.proposedSolution(), request.methodology(), request.dataSources(), request.technology(), request.intendedUsers(),
                authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/proposals/" + created.id())).eTag(etag(created.rowVersion())).body(created);
    }

    @GetMapping("/discovery-runs")
    @PreAuthorize("isAuthenticated()")
    public List<DiscoveryRun> discoveryRuns(Authentication authentication) {
        return workspace.discoveries().stream()
                .filter(run -> projectAccess.canAccessProposal(authentication, run.proposalId()))
                .map(run -> authorizedDiscovery(run, authentication)).toList();
    }

    @GetMapping("/discovery-runs/{id}")
    @PreAuthorize("isAuthenticated()")
    public DiscoveryRun discoveryRun(@PathVariable UUID id, Authentication authentication) {
        DiscoveryRun run = workspace.discovery(id);
        projectAccess.requireProposalAccess(authentication, run.proposalId());
        return authorizedDiscovery(run, authentication);
    }

    @PostMapping("/discovery-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public UiContracts.DiscoveryView runDiscovery(@Valid @RequestBody DiscoveryRequest request, Authentication authentication) {
        projectAccess.requireProposalAccess(authentication, request.proposalId());
        return ui.discovery(workspace.runDiscovery(request.proposalId(), authentication.getName()), authentication);
    }

    @GetMapping("/proposal-decisions")
    @PreAuthorize("isAuthenticated()")
    public List<ProposalDecision> decisions(Authentication authentication) {
        return workspace.decisions().stream().filter(decision -> projectAccess.canAccessProposal(authentication, decision.proposalId())).toList();
    }

    @GetMapping("/proposals/{id}/decision-context/{discoveryRunId}")
    @PreAuthorize("isAuthenticated()")
    public DecisionContext decisionContext(@PathVariable UUID id, @PathVariable UUID discoveryRunId,
            Authentication authentication) {
        projectAccess.requireProposalAccess(authentication, id);
        DiscoveryRun run = workspace.discovery(discoveryRunId);
        if (!run.proposalId().equals(id)) throw new IllegalArgumentException("The frozen discovery run does not belong to this proposal.");
        ProposalDecision decision = workspace.decisions().stream().filter(value -> value.proposalId().equals(id)).findFirst().orElse(null);
        return new DecisionContext(workspace.proposal(id), workspace.proposalObjectiveRecords(id),
                authorizedDiscovery(run, authentication), ui.discovery(run, authentication).candidates(), decision,
                actions.adviserRecommendations(id, discoveryRunId));
    }

    @PostMapping("/proposal-decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProposalDecision decide(@Valid @RequestBody DecisionRequest request, Authentication principal) {
        projectAccess.requireProposalAccess(principal, request.proposalId());
        if (request.primaryPredecessorId() != null) {
            Study candidate = workspace.study(request.primaryPredecessorId());
            studyVisibility.requireVisible(principal, candidate.visibility(), candidate.department());
        }
        ProposalDecision decision = workspace.decide(request.proposalId(), request.discoveryRunId(), request.disposition(), request.rationale(),
                request.primaryPredecessorId(), principal.getName());
        projectAccess.initializeExplicitMemberships();
        return decision;
    }

    @PostMapping("/proposals/{id}/continuation-evidence")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object continuationEvidence(@PathVariable UUID id, @Valid @RequestBody ContinuationEvidenceRequest request,
            Authentication principal) {
        projectAccess.requireProposalAccess(principal, id);
        Study predecessor = workspace.study(request.predecessorStudyId());
        studyVisibility.requireVisible(principal, predecessor.visibility(), predecessor.department());
        var links = request.objectiveLinks().stream().map(link -> new RoutingEvidenceRepository.ObjectiveLink(
                link.proposalObjectiveId(), link.continuationItemId(), link.rationale())).toList();
        return actions.continuationEvidence(id, request.predecessorStudyId(), links, request.codeAccessConfirmed(),
                request.dataAccessConfirmed(), request.accessNotes(), principal.getName());
    }

    @PostMapping("/proposals/{id}/improvement-claims")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object improvementClaim(@PathVariable UUID id, @Valid @RequestBody ImprovementClaimRequest request,
            Authentication principal) {
        projectAccess.requireProposalAccess(principal, id);
        Study predecessor = workspace.study(request.predecessorStudyId());
        studyVisibility.requireVisible(principal, predecessor.visibility(), predecessor.department());
        return actions.improvementClaim(id, request.predecessorStudyId(), request.continuationItemId(), request.claim(),
                request.baselineMeasure(), request.targetMeasure(), request.evaluationMethod(), principal.getName());
    }

    @GetMapping("/proposals/{id}/adviser-recommendations")
    @PreAuthorize("isAuthenticated()")
    public Object adviserRecommendations(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireProposalAccess(authentication, id);
        workspace.proposal(id);
        return actions.adviserRecommendations(id);
    }

    @GetMapping("/proposals/{id}/route-evidence/{predecessorId}")
    @PreAuthorize("isAuthenticated()")
    public Object routeEvidence(@PathVariable UUID id, @PathVariable UUID predecessorId,
            Authentication authentication) {
        projectAccess.requireProposalAccess(authentication, id);
        Study predecessor = workspace.study(predecessorId);
        studyVisibility.requireVisible(authentication, predecessor.visibility(), predecessor.department());
        return workspace.routeAssessment(id, predecessorId);
    }

    @PostMapping("/proposals/{id}/adviser-recommendations")
    @PreAuthorize("hasRole('ADVISER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object adviserRecommendation(@PathVariable UUID id, @Valid @RequestBody AdviserRecommendationRequest request,
            Authentication principal) {
        projectAccess.requireProposalAccess(principal, id);
        return actions.adviserRecommendation(id, request.discoveryRunId(), request.recommendation(), request.rationale(), principal.getName());
    }

    @GetMapping("/projects")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Project>> projects(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return paged(workspace.projects().stream()
                .filter(project -> projectAccess.canAccess(authentication, project.id())).toList(), page, size);
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Project> project(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        Project project = workspace.project(id);
        return ResponseEntity.ok().eTag(etag(project.rowVersion())).body(project);
    }

    @GetMapping("/projects/{id}/traceability")
    public Object traceability(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.traceability(id); }

    @GetMapping("/projects/{id}/trace-graph")
    public Object traceGraph(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        return ui.traceGraph(id, page, size);
    }

    @GetMapping("/projects/{id}/findings")
    public Object findings(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        return workspace.traceability(id).findings().stream().map(finding -> Map.of(
                "finding", finding, "fingerprint", WorkflowActionService.fingerprint(finding),
                "effectiveState", actions.effectiveFindingState(id, finding))).toList();
    }

    @PostMapping("/projects/{id}/findings/{findingId}/{action}")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR')")
    @Transactional
    public ResponseEntity<Object> actOnFinding(@PathVariable UUID id, @PathVariable UUID findingId, @PathVariable String action,
            @Valid @RequestBody FindingActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var finding = workspace.traceability(id).findings().stream().filter(value -> value.id().equals(findingId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Finding is not current for the selected project."));
        FindingState state = switch (action.toLowerCase()) {
            case "resolve" -> FindingState.RESOLVED;
            case "accept" -> FindingState.ACCEPTED;
            case "reopen" -> FindingState.REOPENED;
            default -> throw new IllegalArgumentException("Finding action must be resolve, accept, or reopen.");
        };
        if (state == FindingState.ACCEPTED && principal.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_COORDINATOR"))) {
            throw new org.springframework.security.access.AccessDeniedException("Only a coordinator may accept an exception.");
        }
        var recorded = actions.findingAction(id, finding, state, request.rationale(), request.expiresAt(), principal.getName());
        Project touched = workspace.recordGovernanceMutation(id);
        return ResponseEntity.ok().eTag(etag(touched.rowVersion())).body(Map.of("action", recorded, "project", touched));
    }

    @GetMapping("/projects/{id}/memberships")
    public Object memberships(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        return projectAccess.memberships(id);
    }

    @PostMapping("/projects/{id}/memberships")
    @PreAuthorize("hasRole('CURATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object grantMembership(@PathVariable UUID id, @Valid @RequestBody MembershipRequest request, Principal principal) {
        workspace.project(id);
        return projectAccess.grant(id, request.userId(), request.role(), principal.getName());
    }

    @GetMapping("/projects/{id}/health")
    public Object health(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.health(id); }

    @GetMapping("/projects/{id}/scope-risk")
    public Object scopeRisk(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.scopeRisk(id); }

    @GetMapping("/projects/{id}/lineage")
    public Object lineage(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.lineage(id); }

    @GetMapping("/projects/{id}/completion-package")
    public Object completionPackage(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.completionPackage(id); }

    @GetMapping("/projects/{id}/completion-package/evidence-references")
    public Object completionEvidenceReferences(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        return workspace.completionEvidenceReferences(id).stream().map(EvidenceReferenceResponse::from).toList();
    }

    @PostMapping("/projects/{id}/analysis-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Object analyze(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        requireVersion(workspace.project(id), ifMatch);
        return workspace.rerunAnalysis(id, authentication.getName());
    }

    @PostMapping("/projects/{id}/trace-items")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> createTraceItem(@PathVariable UUID id, @Valid @RequestBody TraceItemRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.createTraceItem(id, request.key(), request.type(), request.title(), request.description(),
                request.priority(), request.acceptanceCriteria(), request.verificationMethod(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + id + "/trace-items/" + result.artifact().id()))
                .eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/trace-items/{itemId}/revisions")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> reviseTraceItem(@PathVariable UUID id, @PathVariable UUID itemId,
            @Valid @RequestBody TraceRevisionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.reviseTraceItem(id, itemId, request.title(), request.description(), request.priority(),
                request.acceptanceCriteria(), request.verificationMethod(), principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/trace-links")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> createTraceLink(@PathVariable UUID id, @Valid @RequestBody TraceLinkRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.createTraceLink(id, request.sourceId(), request.targetId(), request.relationshipType(),
                request.rationale(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + id + "/trace-links/" + result.artifact().id()))
                .eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/test-executions")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> recordTestExecution(@PathVariable UUID id, @Valid @RequestBody TestExecutionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.recordTestExecution(id, request.testItemId(), request.status(), request.buildIdentifier(),
                request.evidenceConfirmed(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + id + "/test-executions/" + result.artifact().id()))
                .eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/baselines/approve")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<Object> approveBaseline(@PathVariable UUID id, @Valid @RequestBody BaselineApprovalRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.approveBaseline(id, request.rationale(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + id + "/baselines/" + result.project().currentBaselineId()))
                .eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/completion-package/evidence")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> updateCompletionEvidence(@PathVariable UUID id,
            @Valid @RequestBody CompletionEvidenceRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var references = request.evidenceReferences() == null ? List.<WorkspaceService.EvidenceReferenceInput>of()
                : request.evidenceReferences().stream().map(value -> new WorkspaceService.EvidenceReferenceInput(
                        value.type(), value.label(), value.location(), value.storedDocumentId(), value.sha256())).toList();
        var result = workspace.updateCompletionEvidence(id, request.repositoryUrl(),
                request.commitHash(), request.setupInstructions(), request.limitations(), request.recommendations(),
                request.unfinishedWork(), references, principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/completion-package/evidence-references/{referenceId}/verification")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','REVIEWER')")
    public ResponseEntity<Object> verifyCompletionEvidence(@PathVariable UUID id, @PathVariable UUID referenceId,
            @Valid @RequestBody EvidenceVerificationRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.verifyCompletionReference(id, referenceId, request.verificationState(),
                request.notes(), principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/complete")
    @Transactional
    public ResponseEntity<Object> complete(@PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        Set<UUID> nonBlocking = workspace.traceability(id).findings().stream()
                .filter(finding -> Set.of(FindingState.RESOLVED, FindingState.ACCEPTED).contains(actions.effectiveFindingState(id, finding)))
                .map(com.ugnay.platform.shared.PlatformModels.Finding::id).collect(java.util.stream.Collectors.toSet());
        Map<String, Object> assessment = workspace.completionAssessment(id, principal.getName(), nonBlocking);
        if (assessment.containsKey("catalogueStudy")) {
            Study completedStudy = (Study) assessment.get("catalogueStudy");
            catalogueMetadata.recordCurrentSnapshot(completedStudy.id(), principal.getName(), "PROJECT_COMPLETION");
            events.publishEvent(new WarehouseRefreshRequested(principal.getName(), WarehouseRefreshRequested.Trigger.PROJECT_COMPLETION));
        }
        return ResponseEntity.ok().eTag(etag(workspace.project(id).rowVersion())).body(assessment);
    }

    @GetMapping("/change-requests")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChangeRequest>> changes(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return paged(workspace.changes().stream()
                .filter(change -> projectAccess.canAccess(authentication, change.projectId())).toList(), page, size);
    }

    @PostMapping("/change-requests")
    public ResponseEntity<ChangeRequest> createChange(@Valid @RequestBody ChangeInput request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication authentication) {
        Project project = workspace.project(request.projectId());
        projectAccess.requireAccess(authentication, request.projectId());
        requireVersion(project, ifMatch);
        ChangeRequest created = workspace.createChange(request.projectId(), request.basedOnBaselineId(), request.title(),
                request.rationale(), request.changedItemIds(), request.boundaryFlags(), authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/change-requests/" + created.id())).eTag(etag(created.rowVersion())).body(created);
    }

    @GetMapping("/change-requests/{id}")
    public ChangeRequest change(@PathVariable UUID id, Authentication authentication) {
        ChangeRequest change = workspace.change(id); projectAccess.requireAccess(authentication, change.projectId()); return change;
    }

    @PostMapping("/change-requests/{id}/preview-impact")
    public Object previewImpact(@PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication authentication) {
        ChangeRequest change = workspace.change(id);
        projectAccess.requireAccess(authentication, change.projectId());
        requireVersion(workspace.project(change.projectId()), ifMatch);
        return workspace.previewImpact(id, authentication.getName());
    }

    @GetMapping("/change-requests/{id}/impact")
    public Object impact(@PathVariable UUID id, Authentication authentication) {
        ChangeRequest change = workspace.change(id); projectAccess.requireAccess(authentication, change.projectId()); return workspace.impact(id);
    }

    @GetMapping("/change-requests/{id}/operations")
    public Object changeOperations(@PathVariable UUID id, Authentication authentication) {
        ChangeRequest change = workspace.change(id); projectAccess.requireAccess(authentication, change.projectId());
        return actions.changeOperations(id);
    }

    @PostMapping("/change-requests/{id}/operations")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object addChangeOperation(@PathVariable UUID id, @Valid @RequestBody ChangeOperationRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        ChangeRequest change = workspace.change(id); projectAccess.requireAccess(principal, change.projectId());
        requireVersion(workspace.project(change.projectId()), ifMatch);
        return actions.addChangeOperation(id, request.type(), request.targetItemId(), request.itemType(), request.itemKey(),
                request.title(), request.description(), request.priority(), request.acceptanceCriteria(), request.verificationMethod(),
                request.sourceItemId(), request.linkTargetItemId(), request.relationshipType(), request.removeRelationship(),
                request.rationale(), principal.getName());
    }

    @PostMapping("/change-requests/{id}/{decision}")
    @PreAuthorize("hasRole('COORDINATOR')")
    @Transactional
    public ResponseEntity<Object> decideChange(@PathVariable UUID id, @PathVariable String decision,
            @Valid @RequestBody ChangeDecisionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        ChangeRequest change = workspace.change(id); projectAccess.requireAccess(principal, change.projectId());
        requireVersion(workspace.project(change.projectId()), ifMatch);
        ChangeDecisionDisposition disposition = switch (decision.toLowerCase()) {
            case "approve" -> ChangeDecisionDisposition.APPROVE;
            case "reject" -> ChangeDecisionDisposition.REJECT;
            case "return-for-revision" -> ChangeDecisionDisposition.RETURN_FOR_REVISION;
            default -> throw new IllegalArgumentException("Change action must approve, reject, or return-for-revision.");
        };
        if (disposition == ChangeDecisionDisposition.APPROVE) {
            if (request.operationSetVersion() == null) {
                throw new IllegalArgumentException("Approval requires the operation-set version returned by the current impact preview.");
            }
            if (request.operationSetVersion() != actions.operationSetVersion(id)) {
                throw new IllegalArgumentException("The typed operation set changed; calculate and review a new impact preview.");
            }
        }
        var result = workspace.decideChange(id, disposition, actions.changeOperations(id), request.rationale(), principal.getName());
        UUID baseline = disposition == ChangeDecisionDisposition.APPROVE ? result.project().currentBaselineId() : null;
        actions.changeDecision(id, disposition, request.rationale(), baseline, principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @GetMapping("/continuation-items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<com.ugnay.platform.shared.PlatformModels.ContinuationItem>> continuationItems(
            @RequestParam UUID projectId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, Authentication authentication) {
        projectAccess.requireAccess(authentication, projectId);
        Set<UUID> predecessorIds = workspace.lineage(projectId).nodes().stream()
                .filter(node -> "STUDY".equals(node.kind())).map(com.ugnay.platform.shared.PlatformModels.LineageNode::id)
                .collect(java.util.stream.Collectors.toSet());
        List<com.ugnay.platform.shared.PlatformModels.ContinuationItem> items = workspace.studies().stream().filter(study -> predecessorIds.contains(study.id()))
                .filter(study -> studyVisibility.canView(authentication, study.visibility(), study.department()))
                .flatMap(study -> study.continuationItems().stream()).toList();
        return paged(items, page, size);
    }

    @PostMapping("/projects/{id}/continuation-claims")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object claimContinuation(@PathVariable UUID id, @Valid @RequestBody ContinuationClaimRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id); requireVersion(workspace.project(id), ifMatch);
        return actions.createClaim(id, request.continuationItemId(), request.successorObjectiveId(), request.rationale(), principal.getName());
    }

    @PostMapping("/continuation-claims/{id}/outcomes")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object appendClaimOutcome(@PathVariable UUID id, @Valid @RequestBody ContinuationOutcomeRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        UUID projectId = actions.claimProjectId(id); projectAccess.requireAccess(principal, projectId);
        requireVersion(workspace.project(projectId), ifMatch);
        return actions.appendClaimOutcome(id, request.outcome(), request.summary(), request.evidenceDocumentId(),
                request.evidenceTraceItemId(), principal.getName());
    }

    @PostMapping("/lineage/check")
    public Object checkLineage(@Valid @RequestBody LineageCheck request, Authentication authentication) {
        projectAccess.requireAccess(authentication, request.projectId());
        boolean cycle = workspace.wouldCreateLineageCycle(request.projectId(), request.sourceId(), request.targetId());
        return Map.of("valid", !cycle, "wouldCreateCycle", cycle, "lineageType", request.lineageType());
    }

    @GetMapping("/review-queue")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> reviewQueue(@RequestParam UUID projectId, Authentication authentication) {
        projectAccess.requireAccess(authentication, projectId);
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
                .location(URI.create("/api/v1/projects/" + projectId + "/reviews")).build();
    }

    @GetMapping("/projects/{id}/reviews")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<JdbcWorkspaceStore.ResearchReviewRecord>> projectReviews(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        return paged(workspace.researchReviews(id), page, size);
    }

    @PostMapping("/projects/{id}/reviews/{reviewId}/revision-requests")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','REVIEWER')")
    public ResponseEntity<Object> requestReviewRevision(@PathVariable UUID id, @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewMessageRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var review = workspace.researchReview(id, reviewId);
        String requiredAuthority = "ROLE_" + review.requiredRole();
        boolean allowed = principal.getAuthorities().stream().anyMatch(authority ->
                "ROLE_COORDINATOR".equals(authority.getAuthority()) || requiredAuthority.equals(authority.getAuthority()));
        if (!allowed) throw new org.springframework.security.access.AccessDeniedException(
                "This review requires the persisted " + review.requiredRole() + " academic role.");
        var result = workspace.appendReviewEvent(id, reviewId, "REVISION_REQUESTED", request.message(),
                request.evidenceLocation(), principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @PostMapping("/projects/{id}/reviews/{reviewId}/revision-responses")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public ResponseEntity<Object> respondToReviewRevision(@PathVariable UUID id, @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewMessageRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication principal) {
        projectAccess.requireAccess(principal, id);
        requireVersion(workspace.project(id), ifMatch);
        var result = workspace.appendReviewEvent(id, reviewId, "REVISION_RESPONDED", request.message(),
                request.evidenceLocation(), principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @GetMapping("/algorithm-disclosure")
    public Object algorithmDisclosure() { return workspace.algorithmDisclosure(); }

    @GetMapping("/users")
    public Object users(Principal principal) {
        audit.append(principal.getName(), "ADMIN_USERS_VIEWED", "USER_DIRECTORY", null, "Viewed the account directory.", Map.of());
        return identities.users();
    }

    @GetMapping("/invitations")
    public Object invitations(Principal principal) {
        audit.append(principal.getName(), "ADMIN_INVITATIONS_VIEWED", "INVITATION_DIRECTORY", null, "Viewed invitation records.", Map.of());
        return identities.invitations();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Object invite(@Valid @RequestBody InvitationRequest request, Principal principal) {
        return identities.invite(request.email(), request.role(), principal.getName());
    }

    @GetMapping("/audit-events")
    public Object auditEvents(Principal principal) {
        audit.append(principal.getName(), "ADMIN_AUDIT_VIEWED", "AUDIT_LOG", null, "Viewed append-only audit history.", Map.of());
        return audit.list(200);
    }

    @GetMapping("/analysis-jobs/{projectId}/events")
    public SseEmitter events(@PathVariable UUID projectId, Authentication authentication) throws IOException {
        projectAccess.requireAccess(authentication, projectId);
        workspace.project(projectId);
        SseEmitter emitter = new SseEmitter(5_000L);
        emitter.send(SseEmitter.event().name("progress").data(Map.of("status", "STARTED", "progress", 0)));
        emitter.send(SseEmitter.event().name("progress").data(Map.of("status", "COMPLETED", "progress", 100, "health", workspace.health(projectId))));
        emitter.complete();
        return emitter;
    }

    private static void requireVersion(Project project, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.equals("*")) {
            throw new PreconditionRequiredException("This mutation requires the exact project ETag in If-Match.");
        }
        if (!ifMatch.equals(etag(project.rowVersion()))) throw new PreconditionFailedException("The project changed; reload it before applying this action.");
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private DiscoveryRun authorizedDiscovery(DiscoveryRun run, Authentication authentication) {
        if (isCurator(authentication)) return run;
        List<DiscoveryCandidate> candidates = run.candidates().stream().filter(candidate -> {
            Study study = workspace.study(candidate.studyId());
            return studyVisibility.canView(authentication, study.visibility(), study.department());
        }).toList();
        return new DiscoveryRun(run.id(), run.proposalId(), run.assessmentStatus(), run.recommendation(),
                run.confidenceState(), run.confidence(),
                run.algorithmVersion(), run.inputHash(), run.semanticProvider(), run.explanation(), run.revisionChecklist(),
                candidates, run.createdAt());
    }

    private static boolean restricted(Study study) {
        return "RESTRICTED".equals(study.visibility()) || "EMBARGOED".equals(study.visibility());
    }

    private static boolean isCurator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_CURATOR"));
    }

    private static <T> ResponseEntity<List<T>> paged(List<T> values, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater.");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Page size must be from 1 to 100.");
        int total = values.size();
        int from = (int) Math.min(total, (long) page * size);
        int to = Math.min(total, from + size);
        List<T> content = values.subList(from, to);
        return ResponseEntity.ok().headers(pageHeaders(page, size, total, content.size())).body(content);
    }

    private static HttpHeaders pageHeaders(int page, int size, int total, int returned) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Page", Integer.toString(page));
        headers.set("X-Page-Size", Integer.toString(size));
        headers.set("X-Total-Count", Integer.toString(total));
        headers.set("X-Truncated", Boolean.toString((long) page * size > 0 || returned < total));
        return headers;
    }

    public record StudyImportRequest(
            @NotBlank String institutionalCode, @NotBlank String title, String academicYear,
            @NotBlank String abstractText, @NotBlank String problemStatement, @NotEmpty List<@NotBlank String> objectives,
            @NotEmpty List<@NotBlank String> keywords, @NotBlank String methodology, @NotBlank String features,
            @NotBlank String stakeholders, @NotBlank String siteContext,
            String department, String program, List<@NotBlank String> authors, String doi, String repositoryIdentifier,
            String dataSources, String technology, String intendedUsers, String resultsText,
            List<@NotBlank String> researchAreas, String visibility, String lifecycleStatus) {}
    public record PublishStudyRequest(
            @NotBlank String institutionalCode, @NotBlank String title, String academicYear,
            @NotBlank String program, @NotEmpty List<@NotBlank String> authors, String doi, String repositoryIdentifier,
            @NotBlank String abstractText, @NotBlank String problemStatement,
            @NotEmpty List<@NotBlank String> objectives, @NotEmpty List<@NotBlank String> keywords,
            @NotBlank String methodology, @NotBlank String features, @NotBlank String stakeholders,
            @NotBlank String siteContext, String department, String dataSources, String technology,
            String intendedUsers, String resultsText, List<@NotBlank String> researchAreas,
            String visibility, String lifecycleStatus) {}

    public record ProblemRequest(
            @NotBlank String title, @NotBlank @Size(min = 40) String problemStatement, @NotBlank String stakeholder,
            @NotBlank String affectedUsers, @NotBlank String siteContext, @NotBlank String desiredOutcome,
            String constraints, @NotBlank String privacyClassification, int evidenceCount) {}

    public record ProposalRequest(
            @NotNull UUID problemId, @NotBlank String title, @NotEmpty List<@NotBlank String> objectives,
            @NotBlank String proposedSolution, String methodology, String dataSources, String technology, String intendedUsers) {}

    public record IntakeRequest(@NotNull @Valid IntakeProblemRequest problem, @NotNull @Valid IntakeProposalRequest proposal,
            List<@Valid EvidenceReferenceRequest> evidenceReferences) {}
    public record IntakeProblemRequest(
            @NotBlank @Size(max = 400) String title, @NotBlank @Size(min = 40) String problemStatement,
            @NotBlank String stakeholder, @NotBlank String affectedUsers, @NotBlank String siteContext,
            @NotBlank String desiredOutcome, String constraints, @NotBlank String privacyClassification) {}
    public record IntakeProposalRequest(
            @NotBlank @Size(max = 500) String title, @NotEmpty List<@NotBlank String> objectives,
            @NotBlank String proposedSolution, String methodology, String dataSources, String technology,
            String intendedUsers) {}
    public record EvidenceReferenceRequest(
            @NotBlank String type, @NotBlank @Size(max = 300) String label, @Size(max = 1000) String location,
            UUID storedDocumentId, String sha256) {}
    public record EvidenceReferenceResponse(UUID id, String type, String label, String location, UUID storedDocumentId,
            String sha256, String verificationState, Instant capturedAt) {
        static EvidenceReferenceResponse from(JdbcWorkspaceStore.EvidenceReferenceRecord value) {
            return new EvidenceReferenceResponse(value.id(), value.type(), value.label(), value.location(), value.documentId(),
                    value.sha256(), value.verificationState(), value.capturedAt());
        }
    }
    public record IntakeResponse(String idempotencyKey, boolean replayed, ProblemCase problem, Proposal proposal,
            UiContracts.DiscoveryView discovery, List<JdbcWorkspaceStore.EvidenceReferenceRecord> evidenceReferences) {}

    public record DiscoveryRequest(@NotNull UUID proposalId) {}

    public record DecisionContext(Proposal proposal, List<JdbcWorkspaceStore.ProposalObjectiveRecord> proposalObjectives,
            DiscoveryRun discovery, List<UiContracts.StudyView> candidateStudies, ProposalDecision decision,
            List<WorkflowActionService.AdviserRecommendation> adviserRecommendations) {}

    public record DecisionRequest(
            @NotNull UUID proposalId, @NotNull UUID discoveryRunId, @NotNull DecisionDisposition disposition,
            @NotBlank @Size(min = 20) String rationale, UUID primaryPredecessorId) {}
    public record ContinuationEvidenceRequest(
            @NotNull UUID predecessorStudyId, @NotEmpty List<@Valid ObjectiveContinuationLinkRequest> objectiveLinks,
            boolean codeAccessConfirmed, boolean dataAccessConfirmed, @NotBlank String accessNotes) {}
    public record ObjectiveContinuationLinkRequest(
            @NotNull UUID proposalObjectiveId, @NotNull UUID continuationItemId, @NotBlank String rationale) {}
    public record ImprovementClaimRequest(
            @NotNull UUID predecessorStudyId, @NotNull UUID continuationItemId, @NotBlank String claim,
            @NotBlank String baselineMeasure, @NotBlank String targetMeasure, @NotBlank String evaluationMethod) {}
    public record AdviserRecommendationRequest(
            @NotNull UUID discoveryRunId, @NotNull Recommendation recommendation, @NotBlank @Size(min = 20) String rationale) {}
    public record FindingActionRequest(@NotBlank @Size(min = 12) String rationale, Instant expiresAt) {}
    public record MembershipRequest(@NotNull UUID userId, @NotBlank String role) {}

    public record ChangeInput(
            @NotNull UUID projectId, @NotNull UUID basedOnBaselineId, @NotBlank String title,
            @NotBlank @Size(min = 20) String rationale, @NotEmpty List<UUID> changedItemIds, List<String> boundaryFlags) {}
    public record ChangeOperationRequest(
            @NotNull ChangeOperationType type, UUID targetItemId,
            com.ugnay.platform.shared.PlatformModels.TraceItemType itemType, String itemKey, String title,
            String description, String priority, String acceptanceCriteria, String verificationMethod,
            UUID sourceItemId, UUID linkTargetItemId, String relationshipType, boolean removeRelationship,
            @NotBlank @Size(min = 10) String rationale) {}
    public record ChangeDecisionRequest(@NotBlank @Size(min = 20) String rationale, Long operationSetVersion) {}
    public record ContinuationClaimRequest(@NotNull UUID continuationItemId, @NotNull UUID successorObjectiveId,
            @NotBlank @Size(min = 12) String rationale) {}
    public record ContinuationOutcomeRequest(@NotNull ContinuationClaimOutcome outcome, @NotBlank @Size(min = 12) String summary,
            UUID evidenceDocumentId, UUID evidenceTraceItemId) {}

    public record LineageCheck(@NotNull UUID projectId, @NotNull UUID sourceId, @NotNull UUID targetId,
                               @NotNull LineageType lineageType) {}
    public record InvitationRequest(@NotBlank @Email String email, @NotBlank String role) {}
    public record TraceItemRequest(
            @NotBlank @Size(max = 64) String key, @NotNull com.ugnay.platform.shared.PlatformModels.TraceItemType type,
            @NotBlank @Size(max = 500) String title, @NotBlank @Size(min = 10) String description,
            String priority, String acceptanceCriteria, String verificationMethod) {}
    public record TraceRevisionRequest(
            @NotBlank @Size(max = 500) String title, @NotBlank @Size(min = 10) String description,
            String priority, String acceptanceCriteria, String verificationMethod) {}
    public record TraceLinkRequest(
            @NotNull UUID sourceId, @NotNull UUID targetId, @NotBlank String relationshipType,
            @NotBlank @Size(min = 10) String rationale) {}
    public record TestExecutionRequest(
            @NotNull UUID testItemId, @NotBlank String status, @NotBlank @Size(max = 160) String buildIdentifier,
            boolean evidenceConfirmed) {}
    public record BaselineApprovalRequest(@NotBlank @Size(min = 20) String rationale) {}
    public record CompletionEvidenceRequest(
            @Size(max = 700) String repositoryUrl,
            @Size(max = 80) String commitHash, String setupInstructions,
            List<@NotBlank String> limitations, List<@NotBlank String> recommendations,
            List<@NotBlank String> unfinishedWork, List<@Valid CompletionEvidenceReferenceRequest> evidenceReferences) {}
    public record CompletionEvidenceReferenceRequest(
            @NotBlank String type, @NotBlank @Size(max = 300) String label, @Size(max = 1000) String location,
            UUID storedDocumentId, String sha256) {}
    public record EvidenceVerificationRequest(@NotBlank String verificationState,
            @NotBlank @Size(min = 20, max = 1000) String notes) {}
    public record ReviewMessageRequest(@NotBlank @Size(min = 20, max = 2000) String message,
            @Size(max = 1000) String evidenceLocation) {}
}

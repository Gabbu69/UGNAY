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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    private final CatalogueMetadataRepository catalogueMetadata;
    private final ApplicationEventPublisher events;

    public UgnayApiController(WorkspaceService workspace, UiWorkspaceMapper ui,
                              JdbcIdentityService identities, JdbcAuditService audit,
                              ProjectAccessService projectAccess, WorkflowActionService actions,
                              ApplicationEventPublisher events, StudyVisibilityPolicy studyVisibility) {
        this.workspace = workspace;
        this.ui = ui;
        this.identities = identities;
        this.audit = audit;
        this.projectAccess = projectAccess;
        this.studyVisibility = studyVisibility;
        this.actions = actions;
        this.catalogueMetadata = ui.catalogueMetadata();
        this.events = events;
    }

    @GetMapping("/workspace")
    public Object workspace(Authentication authentication, @RequestParam(required = false) UUID projectId) {
        if (projectId != null) projectAccess.requireAccess(authentication, projectId);
        return ui.workspace(authentication, projectId);
    }

    @GetMapping("/dashboard")
    public Object dashboard() {
        Map<String, Object> response = new LinkedHashMap<>(workspace.dashboard());
        response.put("recentStudies", ui.studies().stream().limit(3).toList());
        return response;
    }

    @GetMapping("/studies")
    @PreAuthorize("isAuthenticated()")
    public List<UiContracts.StudyView> studies(Authentication authentication,
            @RequestParam(required = false) String q) {
        List<UiContracts.StudyView> permitted = ui.studies(authentication);
        if (q == null || q.isBlank()) return permitted;
        String query = q.toLowerCase();
        return permitted.stream().filter(study -> (study.title() + " " + study.abstractText() + " " + String.join(" ", study.keywords()))
                .toLowerCase().contains(query)).toList();
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
    public List<ProblemCase> problems() { return workspace.problems(); }

    @GetMapping("/problems/{id}")
    public ProblemCase problem(@PathVariable UUID id) { return workspace.problem(id); }

    @PostMapping("/problems")
    public ResponseEntity<ProblemCase> createProblem(@Valid @RequestBody ProblemRequest request) {
        ProblemCase created = workspace.createProblem(request.title(), request.problemStatement(), request.stakeholder(),
                request.affectedUsers(), request.siteContext(), request.desiredOutcome(), request.constraints(),
                request.privacyClassification(), 0);
        return ResponseEntity.created(URI.create("/api/v1/problems/" + created.id())).eTag(etag(created.rowVersion())).body(created);
    }

    @GetMapping("/proposals")
    public List<Proposal> proposals() { return workspace.proposals(); }

    @GetMapping("/proposals/{id}")
    public ResponseEntity<Proposal> proposal(@PathVariable UUID id) {
        Proposal value = workspace.proposal(id);
        return ResponseEntity.ok().eTag(etag(value.rowVersion())).body(value);
    }

    @PostMapping("/proposals")
    public ResponseEntity<Proposal> createProposal(@Valid @RequestBody ProposalRequest request) {
        Proposal created = workspace.createProposal(request.problemId(), request.title(), request.objectives(),
                request.proposedSolution(), request.methodology(), request.dataSources(), request.technology(), request.intendedUsers());
        return ResponseEntity.created(URI.create("/api/v1/proposals/" + created.id())).eTag(etag(created.rowVersion())).body(created);
    }

    @GetMapping("/discovery-runs")
    public List<DiscoveryRun> discoveryRuns(Authentication authentication) {
        return workspace.discoveries().stream().map(run -> authorizedDiscovery(run, authentication)).toList();
    }

    @GetMapping("/discovery-runs/{id}")
    public DiscoveryRun discoveryRun(@PathVariable UUID id, Authentication authentication) {
        return authorizedDiscovery(workspace.discovery(id), authentication);
    }

    @PostMapping("/discovery-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public UiContracts.DiscoveryView runDiscovery(@Valid @RequestBody DiscoveryRequest request) {
        if (request.proposalId() != null) return ui.discovery(workspace.runDiscovery(request.proposalId()));
        String stakeholder = request.stakeholders() == null ? request.stakeholder() : String.join(", ", request.stakeholders());
        String domain = request.domainTerms() == null ? request.technology() : String.join(" ", request.domainTerms());
        Proposal transientProposal = new Proposal(UUID.randomUUID(), request.title(), request.problemStatement(), stakeholder,
                request.affectedUsers() == null ? stakeholder : request.affectedUsers(), request.siteContext(),
                request.desiredOutcome() == null ? "Determine an evidence-backed research route." : request.desiredOutcome(),
                request.constraints(), request.privacyClassification() == null ? "INTERNAL" : request.privacyClassification(),
                request.objectives(), request.proposedSolution() == null ? "Proposed software study in " + domain : request.proposedSolution(),
                request.methodology(), request.dataSources(), domain, request.intendedUsers() == null ? stakeholder : request.intendedUsers(),
                "DISCOVERY_ONLY", Instant.now(), 0);
        return ui.discovery(workspace.runDiscovery(transientProposal));
    }

    @GetMapping("/proposal-decisions")
    public List<ProposalDecision> decisions() { return workspace.decisions(); }

    @PostMapping("/proposal-decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ProposalDecision decide(@Valid @RequestBody DecisionRequest request, Principal principal) {
        ProposalDecision decision = workspace.decide(request.proposalId(), request.discoveryRunId(), request.disposition(), request.rationale(),
                request.primaryPredecessorId(), principal.getName());
        projectAccess.initializeExplicitMemberships();
        return decision;
    }

    @PostMapping("/proposals/{id}/continuation-evidence")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    public Object continuationEvidence(@PathVariable UUID id, @Valid @RequestBody ContinuationEvidenceRequest request,
            Principal principal) {
        var links = request.objectiveLinks().stream().map(link -> new RoutingEvidenceRepository.ObjectiveLink(
                link.proposalObjectiveId(), link.continuationItemId(), link.rationale())).toList();
        return actions.continuationEvidence(id, request.predecessorStudyId(), links, request.codeAccessConfirmed(),
                request.dataAccessConfirmed(), request.accessNotes(), principal.getName());
    }

    @PostMapping("/proposals/{id}/improvement-claims")
    @PreAuthorize("hasAnyRole('STUDENT','ADVISER','COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Object improvementClaim(@PathVariable UUID id, @Valid @RequestBody ImprovementClaimRequest request,
            Principal principal) {
        return actions.improvementClaim(id, request.predecessorStudyId(), request.continuationItemId(), request.claim(),
                request.baselineMeasure(), request.targetMeasure(), request.evaluationMethod(), principal.getName());
    }

    @GetMapping("/proposals/{id}/adviser-recommendations")
    public Object adviserRecommendations(@PathVariable UUID id) { workspace.proposal(id); return actions.adviserRecommendations(id); }

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
            Principal principal) {
        return actions.adviserRecommendation(id, request.discoveryRunId(), request.recommendation(), request.rationale(), principal.getName());
    }

    @GetMapping("/projects")
    public List<Project> projects(Authentication authentication, @RequestParam(defaultValue = "true") boolean mine) {
        return workspace.projects().stream().filter(project -> !mine || projectAccess.canAccess(authentication, project.id())).toList();
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Project> project(@PathVariable UUID id, Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        Project project = workspace.project(id);
        return ResponseEntity.ok().eTag(etag(project.rowVersion())).body(project);
    }

    @GetMapping("/projects/{id}/traceability")
    public Object traceability(@PathVariable UUID id, Authentication authentication) { projectAccess.requireAccess(authentication, id); return workspace.traceability(id); }

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

    @PostMapping("/projects/{id}/analysis-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Object analyze(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        projectAccess.requireAccess(authentication, id);
        requireVersion(workspace.project(id), ifMatch);
        return workspace.rerunAnalysis(id);
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
        var criteria = request.criteria().stream().map(value -> new WorkspaceService.CriterionEvidence(
                value.key(), value.completion(), value.explanation())).toList();
        var result = workspace.updateCompletionEvidence(id, request.codeDataRightsConfirmed(), request.repositoryUrl(),
                request.commitHash(), request.setupInstructions(), request.limitations(), request.recommendations(),
                request.unfinishedWork(), criteria, principal.getName());
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
    public List<ChangeRequest> changes() { return workspace.changes(); }

    @PostMapping("/change-requests")
    public ResponseEntity<ChangeRequest> createChange(@Valid @RequestBody ChangeInput request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Authentication authentication) {
        Project project = workspace.project(request.projectId());
        projectAccess.requireAccess(authentication, request.projectId());
        requireVersion(project, ifMatch);
        ChangeRequest created = workspace.createChange(request.projectId(), request.basedOnBaselineId(), request.title(),
                request.rationale(), request.changedItemIds(), request.boundaryFlags());
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
        return workspace.previewImpact(id);
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
        var result = workspace.decideChange(id, disposition, actions.changeOperations(id), request.rationale(), principal.getName());
        UUID baseline = disposition == ChangeDecisionDisposition.APPROVE ? result.project().currentBaselineId() : null;
        actions.changeDecision(id, disposition, request.rationale(), baseline, principal.getName());
        return ResponseEntity.ok().eTag(etag(result.project().rowVersion())).body(result);
    }

    @GetMapping("/continuation-items")
    public Object continuationItems(Authentication authentication) {
        return workspace.studies().stream().filter(study -> isCurator(authentication) || !restricted(study))
                .flatMap(study -> study.continuationItems().stream()).toList();
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
    public Object checkLineage(@Valid @RequestBody LineageCheck request) {
        boolean cycle = workspace.wouldCreateLineageCycle(request.projectId(), request.sourceId(), request.targetId());
        return Map.of("valid", !cycle, "wouldCreateCycle", cycle, "lineageType", request.lineageType());
    }

    @GetMapping("/review-queue")
    public Object reviewQueue() { return workspace.reviewQueue(); }

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
        List<DiscoveryCandidate> candidates = run.candidates().stream().map(candidate -> {
            Study study = workspace.study(candidate.studyId());
            if (!restricted(study)) return candidate;
            List<CandidateEvidence> evidence = candidate.evidence().stream().map(item -> new CandidateEvidence(
                    item.field(), item.proposalExcerpt(), "Restricted evidence excerpt.",
                    item.components().stream().map(component -> new ComponentScore(component.component(), component.rawScore(),
                            component.weight(), component.weightedScore(), component.explanation(), List.of())).toList())).toList();
            return new DiscoveryCandidate(candidate.rank(), candidate.studyId(), candidate.studyTitle(), candidate.problemScore(),
                    candidate.objectiveScore(), candidate.solutionScore(), candidate.confidence(), candidate.similarityBand(),
                    candidate.exactMatch(), evidence);
        }).toList();
        return new DiscoveryRun(run.id(), run.proposalId(), run.assessmentStatus(), run.recommendation(), run.confidence(),
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

    public record DiscoveryRequest(
            UUID proposalId, String title, String problemStatement, String stakeholder, String affectedUsers,
            String siteContext, String desiredOutcome, String constraints, String privacyClassification,
            List<String> objectives, String proposedSolution, String methodology, String dataSources,
            String technology, String intendedUsers, List<String> stakeholders, List<String> domainTerms) {}

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
            @NotNull UUID projectId, UUID basedOnBaselineId, @NotBlank String title,
            @NotBlank @Size(min = 20) String rationale, @NotEmpty List<UUID> changedItemIds, List<String> boundaryFlags) {}
    public record ChangeOperationRequest(
            @NotNull ChangeOperationType type, UUID targetItemId,
            com.ugnay.platform.shared.PlatformModels.TraceItemType itemType, String itemKey, String title,
            String description, String priority, String acceptanceCriteria, String verificationMethod,
            UUID sourceItemId, UUID linkTargetItemId, String relationshipType, boolean removeRelationship,
            @NotBlank @Size(min = 10) String rationale) {}
    public record ChangeDecisionRequest(@NotBlank @Size(min = 20) String rationale) {}
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
            boolean codeDataRightsConfirmed, @NotBlank @Size(max = 700) String repositoryUrl,
            @NotBlank @Size(max = 80) String commitHash, @NotBlank String setupInstructions,
            List<@NotBlank String> limitations, List<@NotBlank String> recommendations,
            List<@NotBlank String> unfinishedWork, @NotEmpty List<@Valid CriterionEvidenceRequest> criteria) {}
    public record CriterionEvidenceRequest(
            @NotBlank String key, @DecimalMin("0.0") @DecimalMax("1.0") double completion,
            @NotBlank String explanation) {}
}

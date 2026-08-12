package com.ugnay.platform.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static com.ugnay.platform.evaluation.EvaluationModels.*;

@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {
    private final EvaluationService service;
    private final EvaluationRunWorker worker;

    public EvaluationController(EvaluationService service, EvaluationRunWorker worker) {
        this.service = service;
        this.worker = worker;
    }

    @GetMapping("/datasets")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','CURATOR')")
    public List<DatasetVersionView> datasets() { return service.datasets(); }

    @GetMapping("/datasets/{versionId}")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','CURATOR')")
    public DatasetVersionView dataset(@PathVariable UUID versionId) { return service.dataset(versionId); }

    @PostMapping("/datasets")
    @PreAuthorize("hasRole('CURATOR')")
    public ResponseEntity<DatasetVersionView> createDataset(@Valid @RequestBody DatasetRequest request, Principal principal) {
        DatasetVersionView created = service.createDataset(request.name(), request.description(), request.studyIds(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/evaluation/datasets/" + created.versionId())).body(created);
    }

    @GetMapping("/datasets/{versionId}/queries")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','CURATOR')")
    public List<QueryView> queries(@PathVariable UUID versionId) { return service.queries(versionId); }

    @GetMapping("/datasets/{versionId}/queries/{queryId}/corpus")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','CURATOR')")
    public CorpusReviewPage corpusReview(@PathVariable UUID versionId, @PathVariable UUID queryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            Principal principal) {
        return service.corpusReview(versionId, queryId, principal.getName(), page, size);
    }

    @PostMapping("/datasets/{versionId}/queries")
    @PreAuthorize("hasRole('CURATOR')")
    public ResponseEntity<QueryView> addQuery(@PathVariable UUID versionId, @Valid @RequestBody QueryRequest request,
                                              Principal principal) {
        QueryView created = service.addQuery(versionId, request.toStructuredQuery(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/evaluation/datasets/" + versionId + "/queries#" + created.id())).body(created);
    }

    @PostMapping("/datasets/{versionId}/queries/import")
    @PreAuthorize("hasRole('CURATOR')")
    public ResponseEntity<List<QueryView>> importQueries(@PathVariable UUID versionId,
            @RequestBody @Size(min = 1, max = 500) List<@Valid QueryRequest> requests, Principal principal) {
        List<QueryView> created = requests.stream()
                .map(request -> service.addQuery(versionId, request.toStructuredQuery(), principal.getName())).toList();
        return ResponseEntity.created(URI.create("/api/v1/evaluation/datasets/" + versionId + "/queries")).body(created);
    }

    @PostMapping("/queries/{queryId}/judgments")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR')")
    public ResponseEntity<JudgmentView> judge(@PathVariable UUID queryId,
            @Valid @RequestBody GradeRequest request, Principal principal) {
        JudgmentView created = service.judge(queryId, request.studyId(), request.relevanceGrade(), request.rationale(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/evaluation/queries/" + queryId + "/judgments#" + created.id())).body(created);
    }

    @PostMapping("/queries/{queryId}/qrels")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QrelView> adjudicate(@PathVariable UUID queryId,
            @Valid @RequestBody GradeRequest request, Principal principal) {
        QrelView created = service.adjudicate(queryId, request.studyId(), request.relevanceGrade(), request.rationale(), principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/evaluation/queries/" + queryId + "/qrels#" + created.id())).body(created);
    }

    @PostMapping("/datasets/{versionId}/freeze")
    @PreAuthorize("hasRole('COORDINATOR')")
    public DatasetVersionView freeze(@PathVariable UUID versionId, Principal principal) {
        return service.freeze(versionId, principal.getName());
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('ADVISER','COORDINATOR','CURATOR')")
    public ResponseEntity<RunView> startRun(@Valid @RequestBody RunRequest request, Principal principal) {
        RunView run = service.queueRun(request.datasetVersionId(), principal.getName());
        worker.submit(run.id());
        return ResponseEntity.accepted().location(URI.create("/api/v1/evaluation/runs/" + run.id()))
                .body(service.run(run.id()));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("isAuthenticated()")
    public RunView run(@PathVariable UUID runId, Authentication authentication) {
        return service.runForViewer(runId, hasResearchRole(authentication));
    }

    @GetMapping("/runs/{runId}/report")
    @PreAuthorize("isAuthenticated()")
    public EvaluationReport report(@PathVariable UUID runId, Authentication authentication) {
        return service.reportForViewer(runId, hasRole(authentication, "ROLE_CURATOR"),
                hasResearchRole(authentication));
    }

    @GetMapping(value = "/runs/{runId}/report.csv", produces = "text/csv")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> csv(@PathVariable UUID runId, Authentication authentication) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ugnay-evaluation-" + runId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.csvReportForViewer(runId, hasResearchRole(authentication)));
    }

    @PostMapping("/runs/{runId}/publish")
    @PreAuthorize("hasAnyRole('COORDINATOR','CURATOR')")
    public RunView publish(@PathVariable UUID runId, Principal principal) {
        return service.publishRun(runId, principal.getName());
    }

    @GetMapping("/reports/published")
    @PreAuthorize("isAuthenticated()")
    public PublishedReportPage publishedReports(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.publishedReports(page, size);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(role));
    }

    private static boolean hasResearchRole(Authentication authentication) {
        return hasRole(authentication, "ROLE_ADVISER") || hasRole(authentication, "ROLE_COORDINATOR")
                || hasRole(authentication, "ROLE_CURATOR");
    }

    public record DatasetRequest(
            @NotBlank @Size(max = 240) String name,
            @Size(max = 4000) String description,
            @Size(max = 10000) List<@NotNull UUID> studyIds) {}

    public record QueryRequest(
            @NotBlank @Size(max = 120) String externalKey,
            @NotNull QuerySplit split,
            @NotBlank @Size(max = 600) String title,
            @Size(max = 12000) String problemStatement,
            @Size(max = 30) List<@NotBlank @Size(max = 2000) String> objectives,
            @Size(max = 12000) String proposedSolution,
            @Size(max = 6000) String methodology,
            @Size(max = 6000) String dataSources,
            @Size(max = 6000) String technology,
            @Size(max = 6000) String intendedUsers,
            @Size(max = 6000) String stakeholders,
            @Size(max = 6000) String siteContext) {
        EvaluationService.StructuredQuery toStructuredQuery() {
            return new EvaluationService.StructuredQuery(externalKey, split, title, problemStatement, objectives,
                    proposedSolution, methodology, dataSources, technology, intendedUsers, stakeholders, siteContext);
        }
    }

    public record GradeRequest(
            @NotNull UUID studyId,
            @NotNull @Min(0) @Max(3) Integer relevanceGrade,
            @NotBlank @Size(max = 4000) String rationale) {}

    public record RunRequest(@NotNull UUID datasetVersionId) {}
}

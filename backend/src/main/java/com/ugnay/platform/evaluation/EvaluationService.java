package com.ugnay.platform.evaluation;

import com.ugnay.platform.shared.JdbcAuditService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.ugnay.platform.evaluation.EvaluationModels.*;

/** Coordinates the reproducible experiment lifecycle; it never mutates academic decisions. */
@Service
public class EvaluationService {
    private static final int MAX_CORPUS_SIZE = 10_000;
    private static final int MAX_QUERY_COUNT = 500;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CORPUS_UNAVAILABLE = "One or more requested studies are unavailable for evaluation.";
    private static final String BOUNDARY = "Evaluation compares retrieval evidence only. It cannot approve a thesis, declare plagiarism, certify duplication, or change a New/Improve/Continue decision.";

    private final JdbcEvaluationRepository repository;
    private final EvaluationRetrievalEngine retrieval;
    private final ObjectMapper objectMapper;
    private final JdbcAuditService audit;

    public EvaluationService(JdbcEvaluationRepository repository, EvaluationRetrievalEngine retrieval,
                             ObjectMapper objectMapper, JdbcAuditService audit) {
        this.repository = repository;
        this.retrieval = retrieval;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public record StructuredQuery(String externalKey, QuerySplit split, String title, String problemStatement,
                                  List<String> objectives, String proposedSolution, String methodology,
                                  String dataSources, String technology, String intendedUsers,
                                  String stakeholders, String siteContext) {}

    @Transactional
    public DatasetVersionView createDataset(String name, String description, List<UUID> requestedStudyIds, String actorEmail) {
        String safeName = required(name, "Dataset name", 240);
        if (description != null && description.length() > 4_000) throw new IllegalArgumentException("Dataset description must not exceed 4,000 characters.");
        List<UUID> selected = requestedStudyIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(requestedStudyIds));
        if (selected.size() > MAX_CORPUS_SIZE) throw new IllegalArgumentException("An evaluation corpus may contain at most 10,000 studies.");
        List<JdbcEvaluationRepository.SourceStudy> studies = repository.sourceStudies(selected);
        if (!selected.isEmpty() && studies.size() != selected.size()) throw new IllegalArgumentException(CORPUS_UNAVAILABLE);
        if (studies.isEmpty()) throw new IllegalArgumentException("The evaluation corpus cannot be empty.");
        if (studies.size() > MAX_CORPUS_SIZE) throw new IllegalArgumentException("An evaluation corpus may contain at most 10,000 studies.");

        UUID actorId = repository.requireUserId(actorEmail);
        List<JdbcEvaluationRepository.CorpusInsert> corpus = new ArrayList<>();
        List<String> corpusEvidence = new ArrayList<>();
        for (int index = 0; index < studies.size(); index++) {
            var study = studies.get(index);
            Map<String, Object> snapshot = studySnapshot(study);
            String snapshotJson = json(snapshot);
            String profile = combinedStudyProfile(snapshot);
            String profileSha = sha256(profile);
            corpus.add(new JdbcEvaluationRepository.CorpusInsert(study.id(), index, profileSha, profile, snapshotJson,
                    study.visibility()));
            corpusEvidence.add(study.id() + ":" + profileSha);
        }
        String corpusSha = sha256(String.join("\n", corpusEvidence));
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> manifest = orderedMap(
                "state", "DRAFT", "corpusSha256", corpusSha, "corpusSize", corpus.size(),
                "relevanceGrades", List.of(0, 1, 2, 3), "relevanceThreshold", RELEVANCE_THRESHOLD,
                "cutoffs", CUTOFFS, "primaryK", PRIMARY_K, "createdAt", now.toString());
        repository.createDataset(datasetId, versionId, safeName, blankToNull(description), actorId, now,
                corpusSha, json(manifest), corpus);
        audit.append(actorEmail, "EVALUATION_DATASET_CREATED", "EVALUATION_DATASET_VERSION", versionId,
                "Created a draft retrieval-evaluation dataset over an immutable corpus snapshot.",
                Map.of("corpusSha256", corpusSha, "corpusSize", corpus.size()));
        return repository.dataset(versionId);
    }

    @Transactional
    public QueryView addQuery(UUID versionId, StructuredQuery input, String actorEmail) {
        repository.lockDraft(versionId);
        if (repository.queries(versionId).size() >= MAX_QUERY_COUNT) throw new IllegalArgumentException("A dataset may contain at most 500 queries.");
        String externalKey = required(input.externalKey(), "External query key", 120);
        if (!externalKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("External query keys may use letters, numbers, dot, underscore, and hyphen.");
        }
        QuerySplit split = input.split() == null ? QuerySplit.TEST : input.split();
        String title = required(input.title(), "Query title", 600);
        Map<String, Object> snapshot = querySnapshot(input, title);
        String snapshotJson = json(snapshot);
        String combined = combinedQueryProfile(snapshot);
        String querySha = sha256(snapshotJson);
        UUID actorId = repository.requireUserId(actorEmail);
        UUID queryId = UUID.randomUUID();
        Instant now = Instant.now();
        repository.insertQuery(queryId, versionId, externalKey, split, title, combined, snapshotJson, querySha, actorId, now);
        audit.append(actorEmail, "EVALUATION_QUERY_ADDED", "EVALUATION_QUERY", queryId,
                "Added a structured query to a draft evaluation dataset.",
                Map.of("datasetVersionId", versionId.toString(), "querySha256", querySha, "split", split.name()));
        return repository.queryViews(versionId).stream().filter(value -> value.id().equals(queryId)).findFirst().orElseThrow();
    }

    @Transactional
    public JudgmentView judge(UUID queryId, UUID studyId, Integer grade, String rationale, String actorEmail) {
        int safeGrade = validatedGrade(grade);
        String safeRationale = required(rationale, "Judgment rationale", 4_000);
        JdbcEvaluationRepository.QueryRow query = repository.query(queryId);
        repository.lockDraft(query.datasetVersionId());
        if (!repository.corpusContains(query.datasetVersionId(), studyId)) {
            throw new IllegalArgumentException("A judgment must reference a study in the frozen corpus snapshot.");
        }
        UUID reviewerId = repository.requireUserId(actorEmail);
        JudgmentView view = repository.insertJudgment(queryId, studyId, reviewerId, actorEmail.toLowerCase(), safeGrade,
                safeRationale, Instant.now());
        audit.append(actorEmail, "EVALUATION_JUDGMENT_RECORDED", "EVALUATION_QUERY", queryId,
                "Recorded an independent relevance judgment revision.",
                Map.of("studyId", studyId.toString(), "grade", safeGrade, "revision", view.revision()));
        return view;
    }

    @Transactional
    public QrelView adjudicate(UUID queryId, UUID studyId, Integer grade, String rationale, String actorEmail) {
        int safeGrade = validatedGrade(grade);
        String safeRationale = required(rationale, "Adjudication rationale", 4_000);
        JdbcEvaluationRepository.QueryRow query = repository.query(queryId);
        repository.lockDraft(query.datasetVersionId());
        List<JdbcEvaluationRepository.LatestJudgment> judgments = repository.latestJudgments(queryId, studyId);
        if (judgments.stream().map(JdbcEvaluationRepository.LatestJudgment::reviewerId).distinct().count() < 2) {
            throw new IllegalArgumentException("Adjudication requires current judgments from two distinct reviewers.");
        }
        UUID adjudicatorId = repository.requireUserId(actorEmail);
        QrelView view = repository.insertQrel(queryId, studyId, safeGrade, safeRationale, adjudicatorId,
                actorEmail.toLowerCase(), Instant.now());
        audit.append(actorEmail, "EVALUATION_QREL_ADJUDICATED", "EVALUATION_QUERY", queryId,
                "Adjudicated a graded ground-truth relevance judgment.",
                Map.of("studyId", studyId.toString(), "grade", safeGrade, "revision", view.revision(),
                        "reviewerCount", judgments.size()));
        return view;
    }

    @Transactional
    public DatasetVersionView freeze(UUID versionId, String actorEmail) {
        repository.lockDraft(versionId);
        if (!repository.corpusGloballyShareable(versionId)) throw new IllegalArgumentException(CORPUS_UNAVAILABLE);
        List<JdbcEvaluationRepository.QueryRow> queries = repository.queries(versionId);
        if (queries.isEmpty()) throw new IllegalArgumentException("A dataset cannot be frozen without evaluation queries.");
        for (var pair : repository.judgedPairs(versionId)) {
            long reviewers = repository.latestJudgments(pair.queryId(), pair.studyId()).stream()
                    .map(JdbcEvaluationRepository.LatestJudgment::reviewerId).distinct().count();
            if (reviewers < 2) throw new IllegalArgumentException("Every judged query-study pair needs two independent current reviewers before freeze.");
            if (repository.latestQrels(pair.queryId()).stream().noneMatch(value -> value.studyId().equals(pair.studyId()))) {
                throw new IllegalArgumentException("Every double-reviewed query-study pair must be adjudicated before freeze.");
            }
        }
        List<Map<String, Object>> qrelManifest = new ArrayList<>();
        for (var query : queries) {
            List<JdbcEvaluationRepository.LatestQrel> qrels = repository.latestQrels(query.id());
            if (qrels.stream().noneMatch(value -> value.grade() >= RELEVANCE_THRESHOLD)) {
                throw new IllegalArgumentException("Query " + query.externalKey() + " needs at least one adjudicated relevant study before freeze.");
            }
            for (var qrel : qrels) {
                List<JdbcEvaluationRepository.LatestJudgment> currentJudgments = repository.latestJudgments(query.id(), qrel.studyId());
                long reviewers = currentJudgments.stream().map(JdbcEvaluationRepository.LatestJudgment::reviewerId).distinct().count();
                if (reviewers < 2) throw new IllegalArgumentException("Every adjudicated qrel must retain two independent current judgments.");
                if (currentJudgments.stream().anyMatch(value -> value.judgedAt().isAfter(qrel.adjudicatedAt()))) {
                    throw new IllegalArgumentException("A qrel must be re-adjudicated after any reviewer judgment revision.");
                }
                qrelManifest.add(orderedMap("queryId", query.id().toString(), "studyId", qrel.studyId().toString(),
                        "grade", qrel.grade(), "revision", qrel.revision()));
            }
        }
        qrelManifest.sort(java.util.Comparator.comparing(value -> value.get("queryId") + ":" + value.get("studyId")));
        DatasetVersionView dataset = repository.dataset(versionId);
        Instant now = Instant.now();
        Map<String, Object> manifest = orderedMap(
                "state", "FROZEN", "datasetVersionId", versionId.toString(), "corpusSha256", dataset.corpusSha256(),
                "corpusSize", dataset.corpusSize(),
                "queries", queries.stream().map(value -> orderedMap("id", value.id().toString(), "key", value.externalKey(),
                        "split", value.split().name(), "querySha256", value.querySha256())).toList(),
                "qrels", qrelManifest, "relevanceGrades", List.of(0, 1, 2, 3),
                "relevanceThreshold", RELEVANCE_THRESHOLD, "cutoffs", CUTOFFS, "primaryK", PRIMARY_K,
                "frozenAt", now.toString());
        String manifestJson = json(manifest);
        String datasetSha = sha256(manifestJson);
        UUID actorId = repository.requireUserId(actorEmail);
        repository.freeze(versionId, datasetSha, manifestJson, actorId, now);
        audit.append(actorEmail, "EVALUATION_DATASET_FROZEN", "EVALUATION_DATASET_VERSION", versionId,
                "Froze corpus, structured queries, and independently adjudicated qrels for reproducible comparison.",
                Map.of("datasetSha256", datasetSha, "queryCount", queries.size(), "qrelCount", qrelManifest.size()));
        return repository.dataset(versionId);
    }

    @Transactional
    public RunView queueRun(UUID versionId, String actorEmail) {
        DatasetVersionView dataset = repository.dataset(versionId);
        if (dataset.status() != DatasetStatus.FROZEN || dataset.datasetSha256() == null) {
            throw new IllegalArgumentException("Only a frozen, hashed dataset version can be evaluated.");
        }
        if (!repository.corpusGloballyShareable(versionId)) throw new IllegalArgumentException(CORPUS_UNAVAILABLE);
        UUID actorId = repository.requireUserId(actorEmail);
        Map<String, Object> environment = environmentManifest();
        String environmentJson = json(environment);
        String environmentSha = sha256(environmentJson);
        long seed = new BigInteger(dataset.datasetSha256().substring(0, 16), 16).longValue();
        String codeBuild = codeBuild();
        Map<String, Object> manifest = orderedMap(
                "datasetVersionId", versionId.toString(), "datasetSha256", dataset.datasetSha256(),
                "corpusSha256", dataset.corpusSha256(), "algorithms", List.of(
                        configuration(Algorithm.LEXICAL_KEYWORD), configuration(Algorithm.TF_IDF),
                        configuration(Algorithm.SEMANTIC_E5), configuration(Algorithm.HYBRID)),
                "cutoffs", CUTOFFS, "primaryK", PRIMARY_K, "warmupRuns", 1, "timedRepetitions", 5,
                "executionSeed", seed, "codeBuild", codeBuild, "environmentSha256", environmentSha,
                "semanticProvider", retrieval.semanticConfigurationManifest(),
                "unjudgedPolicy", "NON_RELEVANT", "tieBreak", "STUDY_UUID_ASCENDING");
        String manifestJson = json(manifest);
        UUID runId = repository.createRun(versionId, environmentJson, environmentSha, manifestJson,
                sha256(manifestJson), codeBuild, seed, actorId, Instant.now());
        audit.append(actorEmail, "EVALUATION_RUN_QUEUED", "EVALUATION_RUN", runId,
                "Queued a four-arm comparison against one frozen dataset and qrel set.",
                Map.of("datasetVersionId", versionId.toString(), "datasetSha256", dataset.datasetSha256()));
        return repository.run(runId).view();
    }

    public void executeRun(UUID runId) {
        if (!repository.claimRun(runId, Instant.now())) return;
        try {
            JdbcEvaluationRepository.RunRecord run = repository.run(runId);
            List<JdbcEvaluationRepository.CorpusRow> corpusRows = repository.corpus(run.datasetVersionId());
            List<JdbcEvaluationRepository.QueryRow> queryRows = repository.queries(run.datasetVersionId());
            List<EvaluationRetrievalEngine.StudyProfile> studies = corpusRows.stream().map(value ->
                    new EvaluationRetrievalEngine.StudyProfile(value.studyId(), value.profileText(), fields(value.snapshotJson()))).toList();
            List<EvaluationRetrievalEngine.QueryProfile> queries = queryRows.stream().map(value ->
                    new EvaluationRetrievalEngine.QueryProfile(value.id(), value.queryText(), fields(value.snapshotJson()))).toList();
            Map<UUID, Map<UUID, Integer>> qrels = new LinkedHashMap<>();
            for (var query : queryRows) {
                Map<UUID, Integer> grades = new LinkedHashMap<>();
                repository.latestQrels(query.id()).forEach(value -> grades.put(value.studyId(), value.grade()));
                qrels.put(query.id(), Map.copyOf(grades));
            }

            int completed = 0;
            boolean partial = false;
            for (Algorithm algorithm : Algorithm.values()) {
                Map<String, Object> config = configuration(algorithm);
                String configJson = json(config);
                UUID algorithmRunId = repository.startAlgorithm(runId, algorithm, configJson, sha256(configJson), Instant.now());
                try {
                    EvaluationRetrievalEngine.Outcome outcome = retrieval.evaluate(algorithm, queries, studies, 5);
                    boolean unavailable = outcome.status() == RunStatus.UNAVAILABLE;
                    Map<Integer, List<EvaluationMetrics.QueryResult>> resultsByK = new LinkedHashMap<>();
                    CUTOFFS.forEach(k -> resultsByK.put(k, new ArrayList<>()));
                    for (var query : queryRows) {
                        List<EvaluationRetrievalEngine.RankedHit> ranking = outcome.rankings().getOrDefault(query.id(), List.of());
                        ranking.forEach(hit -> repository.insertHit(algorithmRunId, query.id(), hit.studyId(), hit.rank(), hit.score()));
                        for (int k : CUTOFFS) {
                            EvaluationMetrics.QueryResult metric = unavailable
                                    ? unavailableMetric(qrels.get(query.id()), k)
                                    : EvaluationMetrics.calculate(ranking.stream().map(EvaluationRetrievalEngine.RankedHit::studyId).toList(),
                                    qrels.get(query.id()), k);
                            repository.insertQueryMetric(algorithmRunId, query.id(), metric);
                            resultsByK.get(k).add(metric);
                        }
                    }
                    for (int k : CUTOFFS) repository.insertAggregateMetric(algorithmRunId,
                            EvaluationMetrics.aggregate(resultsByK.get(k), k));
                    Instant capturedAt = Instant.now();
                    outcome.resources().forEach(sample -> repository.insertResource(algorithmRunId, sample, capturedAt));
                    repository.completeAlgorithm(algorithmRunId, outcome.status(), outcome.unavailableReason(),
                            outcome.indexBuildMillis(), outcome.latencyP50Millis(), outcome.latencyP95Millis(), Instant.now());
                    if (outcome.status() == RunStatus.COMPLETED) completed++;
                    else partial = true;
                } catch (RuntimeException exception) {
                    partial = true;
                    repository.completeAlgorithm(algorithmRunId, RunStatus.FAILED,
                            "Algorithm execution failed safely: " + exception.getClass().getSimpleName() + ".",
                            0, 0, 0, Instant.now());
                }
            }
            RunStatus status = completed == 0 ? RunStatus.FAILED : partial ? RunStatus.PARTIAL : RunStatus.COMPLETED;
            ComparabilityStatus comparability = status == RunStatus.COMPLETED
                    ? ComparabilityStatus.COMPARABLE : completed > 0 ? ComparabilityStatus.PARTIAL : ComparabilityStatus.UNAVAILABLE;
            repository.completeRun(runId, status, comparability, Instant.now());
        } catch (RuntimeException exception) {
            repository.failRun(runId, "Evaluation failed safely: " + exception.getClass().getSimpleName() + ".", Instant.now());
        }
    }

    public List<DatasetVersionView> datasets() { return repository.datasets(); }
    public DatasetVersionView dataset(UUID versionId) { return repository.dataset(versionId); }
    public List<QueryView> queries(UUID versionId) { repository.dataset(versionId); return repository.queryViews(versionId); }
    public RunView run(UUID runId) { return repository.run(runId).view(); }

    public CorpusReviewPage corpusReview(UUID versionId, UUID queryId, String actorEmail, int page, int size) {
        PageBounds bounds = pageBounds(page, size);
        DatasetVersionView dataset = repository.dataset(versionId);
        if (dataset.status() != DatasetStatus.DRAFT) {
            throw new IllegalArgumentException("The corpus review ledger is available only while the dataset is draft.");
        }
        JdbcEvaluationRepository.QueryRow query = repository.query(queryId);
        if (!query.datasetVersionId().equals(versionId)) {
            throw new java.util.NoSuchElementException("Evaluation query was not found in this dataset version.");
        }
        UUID actorId = repository.requireUserId(actorEmail);
        int total = repository.reviewCorpusCount(versionId);
        Map<UUID, JdbcEvaluationRepository.LatestQrel> qrels = repository.latestQrels(queryId).stream()
                .collect(java.util.stream.Collectors.toMap(JdbcEvaluationRepository.LatestQrel::studyId,
                        java.util.function.Function.identity()));
        List<CorpusReviewItem> items = repository.reviewCorpus(versionId, bounds.offset(), bounds.size()).stream()
                .map(corpus -> reviewItem(queryId, corpus, actorId, qrels.get(corpus.studyId()))).toList();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) bounds.size());
        return new CorpusReviewPage(items, bounds.page(), bounds.size(), total, totalPages);
    }

    @Transactional
    public RunView publishRun(UUID runId, String actorEmail) {
        JdbcEvaluationRepository.RunPublicationState state = repository.lockRunForPublication(runId);
        if (state.runStatus() != RunStatus.COMPLETED && state.runStatus() != RunStatus.PARTIAL) {
            throw new IllegalArgumentException("Only a completed or partial terminal evaluation run can be published.");
        }
        if (!repository.corpusGloballyShareable(state.datasetVersionId())) {
            throw new IllegalArgumentException("The evaluation report is unavailable for publication.");
        }
        if (state.reportStatus() == ReportStatus.PUBLISHED) return repository.run(runId).view();
        UUID actorId = repository.requireUserId(actorEmail);
        Instant now = Instant.now();
        if (!repository.publishRun(runId, actorId, now)) {
            throw new IllegalArgumentException("The evaluation report could not be published from its current state.");
        }
        audit.append(actorEmail, "EVALUATION_REPORT_PUBLISHED", "EVALUATION_RUN", runId,
                "Published a terminal human-reviewed evaluation report.",
                Map.of("datasetVersionId", state.datasetVersionId().toString(), "runStatus", state.runStatus().name()));
        return repository.run(runId).view();
    }

    public PublishedReportPage publishedReports(int page, int size) {
        PageBounds bounds = pageBounds(page, size);
        int total = repository.publishedReportCount();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) bounds.size());
        return new PublishedReportPage(repository.publishedReports(bounds.offset(), bounds.size()),
                bounds.page(), bounds.size(), total, totalPages);
    }

    public RunView runForViewer(UUID runId, boolean privateAccess) {
        JdbcEvaluationRepository.RunRecord run = repository.run(runId);
        requireViewerAccess(run, privateAccess);
        return run.view();
    }

    public EvaluationReport report(UUID runId, boolean includeRankedHits) {
        JdbcEvaluationRepository.RunRecord run = repository.run(runId);
        return report(run, includeRankedHits);
    }

    public EvaluationReport reportForViewer(UUID runId, boolean includeRankedHits, boolean privateAccess) {
        JdbcEvaluationRepository.RunRecord run = repository.run(runId);
        requireViewerAccess(run, privateAccess);
        return report(run, includeRankedHits);
    }

    private EvaluationReport report(JdbcEvaluationRepository.RunRecord run, boolean includeRankedHits) {
        UUID runId = run.id();
        List<AlgorithmReport> reports = repository.algorithms(runId).stream().map(algorithm -> new AlgorithmReport(
                algorithm.id(), algorithm.algorithm(), algorithm.version(), algorithm.status(), algorithm.configurationSha(),
                algorithm.unavailableReason(), algorithm.indexBuildMillis(), algorithm.p50(), algorithm.p95(),
                repository.resourceUsage(algorithm.id()),
                repository.aggregateMetrics(algorithm.id()), repository.queryMetrics(algorithm.id()),
                includeRankedHits ? repository.hits(algorithm.id()) : List.of())).toList();
        return new EvaluationReport(run.view(), repository.dataset(run.datasetVersionId()), map(run.environmentJson()),
                map(run.manifestJson()), reports, BOUNDARY);
    }

    public String csvReport(UUID runId) {
        return csv(report(runId, false));
    }

    public String csvReportForViewer(UUID runId, boolean privateAccess) {
        return csv(reportForViewer(runId, false, privateAccess));
    }

    private static String csv(EvaluationReport report) {
        StringBuilder csv = new StringBuilder("run_id,dataset_sha256,algorithm,algorithm_status,k,metric_status,precision,recall,f1,mrr,ndcg,eligible_queries,excluded_queries\r\n");
        for (AlgorithmReport algorithm : report.algorithms()) {
            for (AggregateMetricView metric : algorithm.aggregateMetrics()) {
                csv.append(report.run().id()).append(',').append(report.dataset().datasetSha256()).append(',')
                        .append(algorithm.version()).append(',').append(algorithm.status()).append(',').append(metric.k()).append(',')
                        .append(metric.status()).append(',').append(csvValue(metric.precision())).append(',')
                        .append(csvValue(metric.recall())).append(',').append(csvValue(metric.f1())).append(',')
                        .append(csvValue(metric.mrr())).append(',').append(csvValue(metric.ndcg())).append(',')
                        .append(metric.eligibleQueries()).append(',').append(metric.excludedQueries()).append("\r\n");
            }
        }
        return csv.toString();
    }

    private CorpusReviewItem reviewItem(UUID queryId, JdbcEvaluationRepository.CorpusRow corpus, UUID actorId,
                                        JdbcEvaluationRepository.LatestQrel qrel) {
        List<JdbcEvaluationRepository.LatestJudgment> judgments = repository.latestJudgments(queryId, corpus.studyId());
        long reviewerCount = judgments.stream().map(JdbcEvaluationRepository.LatestJudgment::reviewerId).distinct().count();
        JdbcEvaluationRepository.LatestJudgment actor = judgments.stream()
                .filter(value -> value.reviewerId().equals(actorId)).findFirst().orElse(null);
        boolean adjudicationCurrent = qrel != null && reviewerCount >= 2
                && judgments.stream().noneMatch(value -> value.judgedAt().isAfter(qrel.adjudicatedAt()));
        Map<String, Object> snapshot = map(corpus.snapshotJson());
        ActorJudgmentView actorView = actor == null ? null : new ActorJudgmentView(
                actor.grade(), actor.rationale(), actor.revision(), actor.judgedAt());
        return new CorpusReviewItem(corpus.studyId(), evidence(snapshot.get("title")),
                evidence(snapshot.get("academicYear")), evidence(snapshot.get("department")), actorView,
                (int) reviewerCount, reviewerCount >= 2, qrel == null ? null : qrel.grade(), adjudicationCurrent);
    }

    private void requireViewerAccess(JdbcEvaluationRepository.RunRecord run, boolean privateAccess) {
        if (privateAccess) return;
        if (run.view().reportStatus() != ReportStatus.PUBLISHED
                || !repository.corpusGloballyShareable(run.datasetVersionId())) {
            throw new java.util.NoSuchElementException("Published evaluation report was not found.");
        }
    }

    void recoverInterruptedRuns() {
        repository.requeueInterruptedRuns();
    }

    Optional<UUID> nextPendingRun() { return repository.nextPendingRun(); }

    private Map<String, Object> environmentManifest() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> database = new LinkedHashMap<>(repository.databaseMetadata());
        return orderedMap("javaVersion", System.getProperty("java.version"), "jvm", System.getProperty("java.vm.name"),
                "osName", System.getProperty("os.name"), "osVersion", System.getProperty("os.version"),
                "osArch", System.getProperty("os.arch"), "availableProcessors", runtime.availableProcessors(),
                "maxHeapBytes", runtime.maxMemory(), "database", database,
                "semanticProvider", retrieval.semanticProviderManifest(), "capturedAt", Instant.now().toString(),
                "cachePolicy", "DOCUMENT_EMBEDDINGS_WARM_QUERY_EMBEDDINGS_MEASURED");
    }

    private Map<String, Object> configuration(Algorithm algorithm) {
        return switch (algorithm) {
            case LEXICAL_KEYWORD -> orderedMap("algorithm", algorithm.version(), "normalization", "NFKC_LOWERCASE",
                    "score", "DISTINCT_QUERY_TOKEN_COVERAGE", "tieBreak", "STUDY_UUID_ASCENDING");
            case TF_IDF -> orderedMap("algorithm", algorithm.version(), "tf", "RAW_COUNT",
                    "idf", "LN((N+1)/(DF+1))+1", "similarity", "COSINE", "tieBreak", "STUDY_UUID_ASCENDING");
            case SEMANTIC_E5 -> orderedMap("algorithm", algorithm.version(), "provider", "LOCAL_MULTILINGUAL_E5_ONNX",
                    "queryPrefix", "query: ", "passagePrefix", "passage: ", "unavailablePolicy", "UNAVAILABLE_NOT_ZERO",
                    "semanticProvider", retrieval.semanticConfigurationManifest());
            case HYBRID -> orderedMap("algorithm", algorithm.version(), "semanticWeight", .50, "tfIdfWeight", .35,
                    "controlledConceptWeight", .15, "problemWeight", .65, "solutionWeight", .35,
                    "fieldWeights", orderedMap("problem", orderedMap("title", .15, "problemStatement", .30,
                                    "objectives", .25, "keywords", .10, "stakeholders", .10, "siteContext", .10),
                            "solution", orderedMap("features", .35, "methodology", .25, "dataSources", .15,
                                    "technology", .15, "intendedUsers", .10)),
                    "semanticUnavailablePolicy", "PARTIAL_ZERO_CONTRIBUTION_NO_RESCALE", "tieBreak", "STUDY_UUID_ASCENDING",
                    "semanticProvider", retrieval.semanticConfigurationManifest());
        };
    }

    private static EvaluationMetrics.QueryResult unavailableMetric(Map<UUID, Integer> qrels, int k) {
        int relevant = (int) qrels.values().stream().filter(grade -> grade >= RELEVANCE_THRESHOLD).count();
        return new EvaluationMetrics.QueryResult(k, MetricStatus.UNAVAILABLE, null, null, null, null, null,
                relevant, qrels.size());
    }

    private Map<String, Object> studySnapshot(JdbcEvaluationRepository.SourceStudy study) {
        return orderedMap("studyId", study.id().toString(), "institutionalCode", blankToNull(study.institutionalCode()),
                "title", blankToNull(study.title()), "academicYear", blankToNull(study.academicYear()),
                "department", blankToNull(study.department()), "lifecycleStatus", blankToNull(study.lifecycleStatus()),
                "visibility", blankToNull(study.visibility()), "abstractText", blankToNull(study.abstractText()),
                "problemStatement", blankToNull(study.problemStatement()), "objectives", list(study.objectives()),
                "keywords", list(study.keywords()), "methodology", blankToNull(study.methodology()),
                "features", blankToNull(study.features()), "dataSources", blankToNull(study.dataSources()),
                "technology", blankToNull(study.technology()), "intendedUsers", blankToNull(study.intendedUsers()),
                "stakeholders", blankToNull(study.stakeholders()), "siteContext", blankToNull(study.siteContext()),
                "provenance", "FROZEN_EVALUATION_CORPUS");
    }

    private static Map<String, Object> querySnapshot(StructuredQuery input, String title) {
        return orderedMap("title", title, "problemStatement", blankToNull(input.problemStatement()),
                "objectives", list(input.objectives()), "proposedSolution", blankToNull(input.proposedSolution()),
                "methodology", blankToNull(input.methodology()), "dataSources", blankToNull(input.dataSources()),
                "technology", blankToNull(input.technology()), "intendedUsers", blankToNull(input.intendedUsers()),
                "stakeholders", blankToNull(input.stakeholders()), "siteContext", blankToNull(input.siteContext()));
    }

    private static String combinedStudyProfile(Map<String, Object> snapshot) {
        return join(snapshot, "institutionalCode", "title", "abstractText", "problemStatement", "objectives", "keywords",
                "methodology", "features", "dataSources", "technology", "intendedUsers", "stakeholders", "siteContext");
    }

    private static String combinedQueryProfile(Map<String, Object> snapshot) {
        return join(snapshot, "title", "problemStatement", "objectives", "proposedSolution", "methodology", "dataSources",
                "technology", "intendedUsers", "stakeholders", "siteContext");
    }

    private static String join(Map<String, Object> values, String... keys) {
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Collection<?> collection) collection.forEach(item -> add(parts, item));
            else add(parts, value);
        }
        return String.join(" ", parts);
    }

    private static void add(List<String> values, Object value) {
        if (value != null && !value.toString().isBlank()) values.add(value.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fields(String json) {
        Map<String, Object> raw = map(json);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof Collection<?> collection) result.put(key, collection.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(" ")));
            else result.put(key, value == null ? "" : value.toString());
        });
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            Object value = objectMapper.readValue(json, Object.class);
            // H2's MySQL-compatible JSON column returns a JDBC-bound JSON document
            // as a quoted JSON scalar, while MySQL returns the document itself.
            // Accept both representations without changing the persisted evidence.
            if (value instanceof String nested) value = objectMapper.readValue(nested, Object.class);
            if (value instanceof Map<?, ?> values) {
                Map<String, Object> result = new LinkedHashMap<>();
                values.forEach((key, item) -> result.put(key.toString(), item));
                return result;
            }
            throw new IllegalStateException("Persisted evaluation JSON must contain an object.");
        }
        catch (JacksonException exception) { throw new IllegalStateException("Persisted evaluation JSON is invalid.", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("Evaluation evidence could not be serialized.", exception); }
    }

    private static String required(String value, String label, int limit) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        String result = value.trim();
        if (result.length() > limit) throw new IllegalArgumentException(label + " must not exceed " + limit + " characters.");
        return result;
    }

    private static int validatedGrade(Integer grade) {
        if (grade == null || grade < 0 || grade > 3) {
            throw new IllegalArgumentException("Relevance grade must be an integer from 0 to 3.");
        }
        return grade;
    }

    private static String codeBuild() {
        String version = EvaluationService.class.getPackage().getImplementationVersion();
        return "ugnay-backend/" + (version == null ? "development" : version);
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String csvValue(Object value) { return value == null ? "UNAVAILABLE" : value.toString(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String evidence(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : List.copyOf(values); }

    private static PageBounds pageBounds(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Page size must be from 1 to 100.");
        return new PageBounds(page, size, (long) page * size);
    }

    private record PageBounds(int page, int size, long offset) {}

    private static Map<String, Object> orderedMap(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Ordered map entries must be key/value pairs.");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put(entries[index].toString(), entries[index + 1]);
        return result;
    }
}

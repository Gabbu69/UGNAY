package com.ugnay.platform.evaluation;

import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static com.ugnay.platform.evaluation.EvaluationModels.*;

/** Relational source of truth for immutable datasets, judgments, qrels, and experiment evidence. */
@Component
class JdbcEvaluationRepository {
    private final JdbcTemplate jdbc;

    JdbcEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record SourceStudy(UUID id, String institutionalCode, String title, String academicYear, String department,
                       String lifecycleStatus, String visibility, String abstractText, String problemStatement,
                       List<String> objectives, List<String> keywords, String methodology, String features,
                       String dataSources, String technology, String intendedUsers, String stakeholders,
                       String siteContext) {}
    record CorpusRow(UUID studyId, String profileSha256, String profileText, String snapshotJson) {}
    record QueryRow(UUID id, UUID datasetVersionId, String externalKey, QuerySplit split, String title,
                    String queryText, String snapshotJson, String querySha256) {}
    record LatestJudgment(UUID id, UUID reviewerId, String reviewerEmail, int revision, int grade,
                          String rationale, Instant judgedAt) {}
    record LatestQrel(UUID id, UUID queryId, UUID studyId, int revision, int grade, String rationale,
                      String adjudicatorEmail, Instant adjudicatedAt) {}
    record QueryStudyPair(UUID queryId, UUID studyId) {}
    record RunRecord(UUID id, UUID datasetVersionId, String environmentJson, String manifestJson,
                     RunView view) {}
    record RunPublicationState(UUID id, UUID datasetVersionId, RunStatus runStatus, ReportStatus reportStatus) {}
    record AlgorithmRow(UUID id, Algorithm algorithm, String version, RunStatus status, String configurationSha,
                        String unavailableReason, Long indexBuildMillis, Double p50, Double p95) {}

    UUID requireUserId(String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("An authenticated database account is required.");
        return jdbc.query("SELECT id FROM user_accounts WHERE email=? AND account_status='ACTIVE'",
                (row, index) -> uuid(row.getBytes(1)), email.toLowerCase()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The authenticated account is not registered in UGNAY."));
    }

    List<SourceStudy> sourceStudies(List<UUID> selected) {
        String sql = "SELECT s.*, d.code AS department_code FROM studies s LEFT JOIN departments d ON d.id=s.department_id"
                + " WHERE s.visibility IN ('PUBLIC','CAMPUS')";
        List<Object> arguments = new ArrayList<>();
        if (selected != null && !selected.isEmpty()) {
            sql += " AND s.id IN (" + String.join(",", java.util.Collections.nCopies(selected.size(), "?")) + ")";
            selected.forEach(id -> arguments.add(bytes(id)));
        }
        sql += " ORDER BY s.id";
        List<SourceStudy> studies = jdbc.query(sql, (row, index) -> {
            byte[] id = row.getBytes("id");
            return new SourceStudy(uuid(id), row.getString("institutional_code"), row.getString("title"),
                    row.getString("academic_year"), row.getString("department_code"), row.getString("lifecycle_status"),
                    row.getString("visibility"), row.getString("abstract_text"), row.getString("problem_statement"),
                    objectives(id), keywords(id), row.getString("methodology"), row.getString("features_text"),
                    row.getString("data_sources_text"), row.getString("technology_text"),
                    row.getString("intended_users_text"), row.getString("stakeholders_text"), row.getString("site_context"));
        }, arguments.toArray());
        return studies.stream().sorted(java.util.Comparator.comparing(value -> value.id().toString())).toList();
    }

    @Transactional
    void createDataset(UUID datasetId, UUID versionId, String name, String description, UUID actorId, Instant now,
                       String corpusSha, String manifestJson, List<CorpusInsert> corpus) {
        jdbc.update("INSERT INTO evaluation_datasets(id,name,description,created_by,created_at) VALUES(?,?,?,?,?)",
                bytes(datasetId), name, description, bytes(actorId), timestamp(now));
        jdbc.update("INSERT INTO evaluation_dataset_versions(id,dataset_id,version_number,dataset_status,corpus_sha256,dataset_sha256,manifest_json,created_by,created_at,frozen_by,frozen_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                bytes(versionId), bytes(datasetId), 1, DatasetStatus.DRAFT.name(), corpusSha, null, manifestJson,
                bytes(actorId), timestamp(now), null, null);
        for (CorpusInsert item : corpus) {
            jdbc.update("INSERT INTO evaluation_corpus_items(dataset_version_id,study_id,item_order,study_profile_sha256,profile_text,study_snapshot_json,source_visibility) VALUES(?,?,?,?,?,?,?)",
                    bytes(versionId), bytes(item.studyId()), item.order(), item.profileSha(), item.profileText(),
                    item.snapshotJson(), item.sourceVisibility());
        }
    }

    record CorpusInsert(UUID studyId, int order, String profileSha, String profileText, String snapshotJson,
                        String sourceVisibility) {}

    DatasetVersionView dataset(UUID versionId) {
        return jdbc.query("SELECT d.id AS dataset_id,d.name,d.description,v.*,"
                        + "(SELECT COUNT(*) FROM evaluation_corpus_items c WHERE c.dataset_version_id=v.id) AS corpus_size,"
                        + "(SELECT COUNT(*) FROM evaluation_queries q WHERE q.dataset_version_id=v.id) AS query_count,"
                        + "(SELECT COUNT(*) FROM evaluation_qrels r JOIN evaluation_queries q ON q.id=r.query_id WHERE q.dataset_version_id=v.id AND NOT EXISTS(SELECT 1 FROM evaluation_qrels newer WHERE newer.query_id=r.query_id AND newer.study_id=r.study_id AND newer.revision_number>r.revision_number)) AS qrel_count "
                        + "FROM evaluation_dataset_versions v JOIN evaluation_datasets d ON d.id=v.dataset_id WHERE v.id=?",
                (row, index) -> new DatasetVersionView(uuid(row.getBytes("dataset_id")), uuid(row.getBytes("id")),
                        row.getInt("version_number"), row.getString("name"), row.getString("description"),
                        DatasetStatus.valueOf(row.getString("dataset_status")), row.getString("corpus_sha256"),
                        row.getString("dataset_sha256"), row.getInt("corpus_size"), row.getInt("query_count"),
                        row.getInt("qrel_count"), instant(row.getTimestamp("created_at")), instant(row.getTimestamp("frozen_at"))),
                bytes(versionId)).stream().findFirst().orElseThrow(() -> new NoSuchElementException("Evaluation dataset version was not found."));
    }

    List<DatasetVersionView> datasets() {
        return jdbc.query("SELECT id FROM evaluation_dataset_versions ORDER BY created_at DESC",
                (row, index) -> dataset(uuid(row.getBytes(1))));
    }

    void lockDraft(UUID versionId) {
        DatasetStatus status = jdbc.query(
                        "SELECT dataset_status FROM evaluation_dataset_versions WHERE id=? FOR UPDATE",
                        (row, index) -> DatasetStatus.valueOf(row.getString("dataset_status")), bytes(versionId))
                .stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Evaluation dataset version was not found."));
        if (status != DatasetStatus.DRAFT) throw new IllegalArgumentException("Frozen evaluation evidence cannot be changed.");
    }

    boolean corpusContains(UUID versionId, UUID studyId) {
        return count("SELECT COUNT(*) FROM evaluation_corpus_items c JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=? AND c.study_id=? AND c.source_visibility IN ('PUBLIC','CAMPUS') "
                        + "AND s.visibility IN ('PUBLIC','CAMPUS')",
                bytes(versionId), bytes(studyId)) > 0;
    }

    boolean corpusGloballyShareable(UUID versionId) {
        int total = count("SELECT COUNT(*) FROM evaluation_corpus_items WHERE dataset_version_id=?", bytes(versionId));
        int shareable = count("SELECT COUNT(*) FROM evaluation_corpus_items c JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=? AND c.source_visibility IN ('PUBLIC','CAMPUS') "
                        + "AND s.visibility IN ('PUBLIC','CAMPUS')", bytes(versionId));
        return total > 0 && total == shareable;
    }

    List<CorpusRow> reviewCorpus(UUID versionId, long offset, int size) {
        return jdbc.query("SELECT c.* FROM evaluation_corpus_items c JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=? AND c.source_visibility IN ('PUBLIC','CAMPUS') "
                        + "AND s.visibility IN ('PUBLIC','CAMPUS') ORDER BY c.item_order LIMIT ? OFFSET ?",
                (row, index) -> new CorpusRow(uuid(row.getBytes("study_id")), row.getString("study_profile_sha256"),
                        row.getString("profile_text"), row.getString("study_snapshot_json")),
                bytes(versionId), size, offset);
    }

    int reviewCorpusCount(UUID versionId) {
        return count("SELECT COUNT(*) FROM evaluation_corpus_items c JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=? AND c.source_visibility IN ('PUBLIC','CAMPUS') "
                        + "AND s.visibility IN ('PUBLIC','CAMPUS')", bytes(versionId));
    }

    void insertQuery(UUID id, UUID versionId, String externalKey, QuerySplit split, String title, String queryText,
                     String snapshotJson, String querySha, UUID actorId, Instant now) {
        jdbc.update("INSERT INTO evaluation_queries(id,dataset_version_id,external_key,query_split,query_title,query_text,query_snapshot_json,query_sha256,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(versionId), externalKey, split.name(), title, queryText, snapshotJson, querySha,
                bytes(actorId), timestamp(now));
    }

    QueryRow query(UUID queryId) {
        return jdbc.query("SELECT * FROM evaluation_queries WHERE id=?", (row, index) -> new QueryRow(
                uuid(row.getBytes("id")), uuid(row.getBytes("dataset_version_id")), row.getString("external_key"),
                QuerySplit.valueOf(row.getString("query_split")), row.getString("query_title"), row.getString("query_text"),
                row.getString("query_snapshot_json"), row.getString("query_sha256")), bytes(queryId)).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Evaluation query was not found."));
    }

    List<QueryRow> queries(UUID versionId) {
        return jdbc.query("SELECT * FROM evaluation_queries WHERE dataset_version_id=? ORDER BY external_key,id",
                (row, index) -> new QueryRow(uuid(row.getBytes("id")), uuid(row.getBytes("dataset_version_id")),
                        row.getString("external_key"), QuerySplit.valueOf(row.getString("query_split")),
                        row.getString("query_title"), row.getString("query_text"), row.getString("query_snapshot_json"),
                        row.getString("query_sha256")), bytes(versionId));
    }

    List<QueryView> queryViews(UUID versionId) {
        return jdbc.query("SELECT q.*,(SELECT COUNT(DISTINCT j.reviewer_id) FROM evaluation_judgments j WHERE j.query_id=q.id) AS reviewers,"
                        + "(SELECT COUNT(*) FROM evaluation_qrels r WHERE r.query_id=q.id AND NOT EXISTS(SELECT 1 FROM evaluation_qrels newer WHERE newer.query_id=r.query_id AND newer.study_id=r.study_id AND newer.revision_number>r.revision_number)) AS qrels "
                        + "FROM evaluation_queries q WHERE q.dataset_version_id=? ORDER BY q.external_key,q.id",
                (row, index) -> new QueryView(uuid(row.getBytes("id")), uuid(row.getBytes("dataset_version_id")),
                        row.getString("external_key"), QuerySplit.valueOf(row.getString("query_split")), row.getString("query_title"),
                        row.getString("query_sha256"), row.getInt("reviewers"), row.getInt("qrels"),
                        instant(row.getTimestamp("created_at"))), bytes(versionId));
    }

    List<LatestJudgment> latestJudgments(UUID queryId, UUID studyId) {
        return jdbc.query("SELECT j.*,u.email FROM evaluation_judgments j JOIN user_accounts u ON u.id=j.reviewer_id "
                        + "WHERE j.query_id=? AND j.study_id=? AND NOT EXISTS(SELECT 1 FROM evaluation_judgments newer WHERE newer.query_id=j.query_id AND newer.study_id=j.study_id AND newer.reviewer_id=j.reviewer_id AND newer.revision_number>j.revision_number) ORDER BY u.email",
                (row, index) -> new LatestJudgment(uuid(row.getBytes("id")), uuid(row.getBytes("reviewer_id")),
                        row.getString("email"), row.getInt("revision_number"), row.getInt("relevance_grade"),
                        row.getString("rationale"), instant(row.getTimestamp("judged_at"))), bytes(queryId), bytes(studyId));
    }

    JudgmentView insertJudgment(UUID queryId, UUID studyId, UUID reviewerId, String reviewerEmail,
                                int grade, String rationale, Instant now) {
        List<LatestJudgment> existing = latestJudgments(queryId, studyId).stream()
                .filter(value -> value.reviewerId().equals(reviewerId)).toList();
        int revision = existing.isEmpty() ? 1 : existing.getFirst().revision() + 1;
        UUID supersedes = existing.isEmpty() ? null : existing.getFirst().id();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_judgments(id,query_id,study_id,reviewer_id,revision_number,relevance_grade,rationale,supersedes_judgment_id,judged_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(queryId), bytes(studyId), bytes(reviewerId), revision, grade, rationale,
                nullableBytes(supersedes), timestamp(now));
        return new JudgmentView(id, queryId, studyId, reviewerEmail, revision, grade, rationale, now);
    }

    QrelView insertQrel(UUID queryId, UUID studyId, int grade, String rationale, UUID adjudicatorId,
                        String adjudicatorEmail, Instant now) {
        List<LatestQrel> existing = latestQrels(queryId).stream().filter(value -> value.studyId().equals(studyId)).toList();
        int revision = existing.isEmpty() ? 1 : existing.getFirst().revision() + 1;
        UUID supersedes = existing.isEmpty() ? null : existing.getFirst().id();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_qrels(id,query_id,study_id,revision_number,relevance_grade,rationale,adjudicated_by,supersedes_qrel_id,adjudicated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(queryId), bytes(studyId), revision, grade, rationale, bytes(adjudicatorId),
                nullableBytes(supersedes), timestamp(now));
        return new QrelView(id, queryId, studyId, revision, grade, rationale, adjudicatorEmail, now);
    }

    List<LatestQrel> latestQrels(UUID queryId) {
        return jdbc.query("SELECT r.*,u.email FROM evaluation_qrels r JOIN user_accounts u ON u.id=r.adjudicated_by "
                        + "WHERE r.query_id=? AND NOT EXISTS(SELECT 1 FROM evaluation_qrels newer WHERE newer.query_id=r.query_id AND newer.study_id=r.study_id AND newer.revision_number>r.revision_number) ORDER BY r.study_id",
                (row, index) -> new LatestQrel(uuid(row.getBytes("id")), uuid(row.getBytes("query_id")),
                        uuid(row.getBytes("study_id")), row.getInt("revision_number"), row.getInt("relevance_grade"),
                        row.getString("rationale"), row.getString("email"), instant(row.getTimestamp("adjudicated_at"))),
                bytes(queryId));
    }

    List<QueryStudyPair> judgedPairs(UUID versionId) {
        return jdbc.query("SELECT DISTINCT j.query_id,j.study_id FROM evaluation_judgments j JOIN evaluation_queries q ON q.id=j.query_id WHERE q.dataset_version_id=? ORDER BY j.query_id,j.study_id",
                (row, index) -> new QueryStudyPair(uuid(row.getBytes("query_id")), uuid(row.getBytes("study_id"))),
                bytes(versionId));
    }

    void freeze(UUID versionId, String datasetSha, String manifestJson, UUID actorId, Instant now) {
        int changed = jdbc.update("UPDATE evaluation_dataset_versions SET dataset_status=?,dataset_sha256=?,manifest_json=?,frozen_by=?,frozen_at=? WHERE id=? AND dataset_status=?",
                DatasetStatus.FROZEN.name(), datasetSha, manifestJson, bytes(actorId), timestamp(now), bytes(versionId), DatasetStatus.DRAFT.name());
        if (changed != 1) throw new IllegalArgumentException("Only a draft dataset version can be frozen.");
    }

    List<CorpusRow> corpus(UUID versionId) {
        return jdbc.query("SELECT * FROM evaluation_corpus_items WHERE dataset_version_id=? ORDER BY item_order",
                (row, index) -> new CorpusRow(uuid(row.getBytes("study_id")), row.getString("study_profile_sha256"),
                        row.getString("profile_text"), row.getString("study_snapshot_json")), bytes(versionId));
    }

    UUID createRun(UUID versionId, String environmentJson, String environmentSha, String manifestJson,
                   String manifestSha, String codeBuild, long seed, UUID actorId, int repetitions, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_runs(id,dataset_version_id,run_status,comparability_status,primary_k,cutoffs_json,repetitions,execution_seed,code_build,environment_json,environment_sha256,run_manifest_json,run_sha256,started_by,queued_at,started_at,completed_at,failure_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(versionId), RunStatus.QUEUED.name(), ComparabilityStatus.UNAVAILABLE.name(), PRIMARY_K,
                "[1,3,5,10]", repetitions, seed, codeBuild, environmentJson, environmentSha, manifestJson, manifestSha,
                bytes(actorId), timestamp(now), null, null, null);
        return id;
    }

    boolean claimRun(UUID runId, Instant now) {
        return jdbc.update("UPDATE evaluation_runs SET run_status=?,started_at=? WHERE id=? AND run_status=?",
                RunStatus.RUNNING.name(), timestamp(now), bytes(runId), RunStatus.QUEUED.name()) == 1;
    }

    void requeueInterruptedRuns() {
        jdbc.update("UPDATE evaluation_algorithm_runs SET run_status=?,unavailable_reason=?,completed_at=? WHERE run_status=? AND evaluation_run_id IN (SELECT id FROM evaluation_runs WHERE run_status=?)",
                RunStatus.FAILED.name(), "Interrupted by application restart; a new append-only attempt will be recorded.",
                timestamp(Instant.now()), RunStatus.RUNNING.name(), RunStatus.RUNNING.name());
        jdbc.update("UPDATE evaluation_runs SET run_status=?,started_at=NULL,failure_reason=? WHERE run_status=?",
                RunStatus.QUEUED.name(), "Recovered after application restart before evaluation completion.", RunStatus.RUNNING.name());
    }

    Optional<UUID> nextPendingRun() {
        return jdbc.query("SELECT id FROM evaluation_runs WHERE run_status=? ORDER BY queued_at LIMIT 1",
                        (row, index) -> uuid(row.getBytes(1)), RunStatus.QUEUED.name())
                .stream().findFirst();
    }

    RunRecord run(UUID runId) {
        return jdbc.query("SELECT * FROM evaluation_runs WHERE id=?", (row, index) -> {
            Timestamp started = row.getTimestamp("started_at");
            Timestamp completed = row.getTimestamp("completed_at");
            RunView view = new RunView(uuid(row.getBytes("id")), uuid(row.getBytes("dataset_version_id")),
                    RunStatus.valueOf(row.getString("run_status")), ComparabilityStatus.valueOf(row.getString("comparability_status")),
                    row.getInt("primary_k"), CUTOFFS, row.getInt("repetitions"), row.getLong("execution_seed"),
                    row.getString("code_build"), row.getString("environment_sha256"), row.getString("run_sha256"),
                    row.getString("failure_reason"), ReportStatus.valueOf(row.getString("report_status")),
                    instant(row.getTimestamp("queued_at")), instant(started), instant(completed),
                    instant(row.getTimestamp("published_at")));
            return new RunRecord(view.id(), view.datasetVersionId(), row.getString("environment_json"),
                    row.getString("run_manifest_json"), view);
        }, bytes(runId)).stream().findFirst().orElseThrow(() -> new NoSuchElementException("Evaluation run was not found."));
    }

    RunPublicationState lockRunForPublication(UUID runId) {
        return jdbc.query("SELECT id,dataset_version_id,run_status,report_status FROM evaluation_runs WHERE id=? FOR UPDATE",
                        (row, index) -> new RunPublicationState(uuid(row.getBytes("id")),
                                uuid(row.getBytes("dataset_version_id")), RunStatus.valueOf(row.getString("run_status")),
                                ReportStatus.valueOf(row.getString("report_status"))), bytes(runId))
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Evaluation run was not found."));
    }

    boolean publishRun(UUID runId, UUID actorId, Instant now) {
        return jdbc.update("UPDATE evaluation_runs SET report_status=?,published_by=?,published_at=? "
                        + "WHERE id=? AND report_status=?",
                ReportStatus.PUBLISHED.name(), bytes(actorId), timestamp(now), bytes(runId),
                ReportStatus.PRIVATE.name()) == 1;
    }

    List<PublishedReportView> publishedReports(long offset, int size) {
        return jdbc.query("SELECT r.id AS run_id,r.dataset_version_id,d.name AS dataset_name,v.dataset_sha256,"
                        + "r.run_status,r.comparability_status,r.code_build,r.published_at "
                        + "FROM evaluation_runs r JOIN evaluation_dataset_versions v ON v.id=r.dataset_version_id "
                        + "JOIN evaluation_datasets d ON d.id=v.dataset_id WHERE r.report_status=? "
                        + "AND NOT EXISTS(SELECT 1 FROM evaluation_corpus_items c LEFT JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=r.dataset_version_id AND (c.source_visibility IS NULL "
                        + "OR c.source_visibility NOT IN ('PUBLIC','CAMPUS') OR s.id IS NULL "
                        + "OR s.visibility NOT IN ('PUBLIC','CAMPUS'))) "
                        + "ORDER BY r.published_at DESC,r.id LIMIT ? OFFSET ?",
                (row, index) -> new PublishedReportView(uuid(row.getBytes("run_id")),
                        uuid(row.getBytes("dataset_version_id")), row.getString("dataset_name"),
                        row.getString("dataset_sha256"), RunStatus.valueOf(row.getString("run_status")),
                        ComparabilityStatus.valueOf(row.getString("comparability_status")), row.getString("code_build"),
                        instant(row.getTimestamp("published_at"))), ReportStatus.PUBLISHED.name(), size, offset);
    }

    int publishedReportCount() {
        return count("SELECT COUNT(*) FROM evaluation_runs r WHERE r.report_status=? "
                        + "AND NOT EXISTS(SELECT 1 FROM evaluation_corpus_items c LEFT JOIN studies s ON s.id=c.study_id "
                        + "WHERE c.dataset_version_id=r.dataset_version_id AND (c.source_visibility IS NULL "
                        + "OR c.source_visibility NOT IN ('PUBLIC','CAMPUS') OR s.id IS NULL "
                        + "OR s.visibility NOT IN ('PUBLIC','CAMPUS')))", ReportStatus.PUBLISHED.name());
    }

    UUID startAlgorithm(UUID runId, Algorithm algorithm, String configurationJson, String configurationSha, Instant now) {
        UUID id = UUID.randomUUID();
        int attempt = count("SELECT COUNT(*) FROM evaluation_algorithm_runs WHERE evaluation_run_id=? AND algorithm_code=?",
                bytes(runId), algorithm.code()) + 1;
        jdbc.update("INSERT INTO evaluation_algorithm_runs(id,evaluation_run_id,algorithm_code,algorithm_version,attempt_number,run_status,configuration_json,configuration_sha256,unavailable_reason,index_build_millis,latency_p50_millis,latency_p95_millis,started_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(runId), algorithm.code(), algorithm.version(), attempt, RunStatus.RUNNING.name(), configurationJson,
                configurationSha, null, null, null, null, timestamp(now), null);
        return id;
    }

    void completeAlgorithm(UUID algorithmRunId, RunStatus status, String reason, long indexMillis,
                           double p50, double p95, Instant now) {
        jdbc.update("UPDATE evaluation_algorithm_runs SET run_status=?,unavailable_reason=?,index_build_millis=?,latency_p50_millis=?,latency_p95_millis=?,completed_at=? WHERE id=?",
                status.name(), reason, indexMillis, p50, p95, timestamp(now), bytes(algorithmRunId));
    }

    void insertHit(UUID algorithmRunId, UUID queryId, UUID studyId, int rank, double score) {
        jdbc.update("INSERT INTO evaluation_ranked_hits(algorithm_run_id,query_id,study_id,candidate_rank,retrieval_score) VALUES(?,?,?,?,?)",
                bytes(algorithmRunId), bytes(queryId), bytes(studyId), rank, score);
    }

    void insertQueryMetric(UUID algorithmRunId, UUID queryId, EvaluationMetrics.QueryResult metric) {
        jdbc.update("INSERT INTO evaluation_query_metrics(algorithm_run_id,query_id,cutoff_k,metric_status,precision_value,recall_value,f1_value,mrr_value,ndcg_value,relevant_count,judged_count) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                bytes(algorithmRunId), bytes(queryId), metric.k(), metric.status().name(), metric.precision(), metric.recall(),
                metric.f1(), metric.mrr(), metric.ndcg(), metric.relevantCount(), metric.judgedCount());
    }

    void insertAggregateMetric(UUID algorithmRunId, EvaluationMetrics.AggregateResult metric) {
        jdbc.update("INSERT INTO evaluation_aggregate_metrics(algorithm_run_id,cutoff_k,metric_status,precision_value,recall_value,f1_value,mrr_value,ndcg_value,eligible_query_count,excluded_query_count) VALUES(?,?,?,?,?,?,?,?,?,?)",
                bytes(algorithmRunId), metric.k(), metric.status().name(), metric.precision(), metric.recall(), metric.f1(),
                metric.mrr(), metric.ndcg(), metric.eligibleQueries(), metric.excludedQueries());
    }

    void insertResource(UUID algorithmRunId, EvaluationRetrievalEngine.ResourceSample sample, Instant capturedAt) {
        jdbc.update("INSERT INTO evaluation_resource_snapshots(id,algorithm_run_id,sample_phase,sample_order,wall_millis,process_cpu_nanos,heap_used_bytes,heap_committed_bytes,captured_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(algorithmRunId), sample.phase(), sample.order(), sample.wallMillis(),
                sample.processCpuNanos(), sample.heapUsedBytes(), sample.heapCommittedBytes(), timestamp(capturedAt));
    }

    void completeRun(UUID runId, RunStatus status, ComparabilityStatus comparability, Instant now) {
        jdbc.update("UPDATE evaluation_runs SET run_status=?,comparability_status=?,completed_at=?,failure_reason=NULL WHERE id=?",
                status.name(), comparability.name(), timestamp(now), bytes(runId));
    }

    void failRun(UUID runId, String reason, Instant now) {
        jdbc.update("UPDATE evaluation_runs SET run_status=?,comparability_status=?,completed_at=?,failure_reason=? WHERE id=?",
                RunStatus.FAILED.name(), ComparabilityStatus.UNAVAILABLE.name(), timestamp(now), truncate(reason, 1000), bytes(runId));
    }

    List<AlgorithmRow> algorithms(UUID runId) {
        return jdbc.query("SELECT * FROM evaluation_algorithm_runs a WHERE evaluation_run_id=? AND NOT EXISTS(SELECT 1 FROM evaluation_algorithm_runs newer WHERE newer.evaluation_run_id=a.evaluation_run_id AND newer.algorithm_code=a.algorithm_code AND newer.attempt_number>a.attempt_number) ORDER BY algorithm_code",
                (row, index) -> new AlgorithmRow(uuid(row.getBytes("id")), algorithm(row.getString("algorithm_code")),
                        row.getString("algorithm_version"), RunStatus.valueOf(row.getString("run_status")),
                        row.getString("configuration_sha256"), row.getString("unavailable_reason"),
                        nullableLong(row, "index_build_millis"), nullableDouble(row, "latency_p50_millis"),
                        nullableDouble(row, "latency_p95_millis")), bytes(runId));
    }

    List<AggregateMetricView> aggregateMetrics(UUID algorithmRunId) {
        return jdbc.query("SELECT * FROM evaluation_aggregate_metrics WHERE algorithm_run_id=? ORDER BY cutoff_k",
                (row, index) -> new AggregateMetricView(row.getInt("cutoff_k"), MetricStatus.valueOf(row.getString("metric_status")),
                        nullableDouble(row, "precision_value"), nullableDouble(row, "recall_value"),
                        nullableDouble(row, "f1_value"), nullableDouble(row, "mrr_value"), nullableDouble(row, "ndcg_value"),
                        row.getInt("eligible_query_count"), row.getInt("excluded_query_count")), bytes(algorithmRunId));
    }

    List<QueryMetricView> queryMetrics(UUID algorithmRunId) {
        return jdbc.query("SELECT m.*,q.external_key FROM evaluation_query_metrics m JOIN evaluation_queries q ON q.id=m.query_id WHERE m.algorithm_run_id=? ORDER BY q.external_key,m.cutoff_k",
                (row, index) -> new QueryMetricView(uuid(row.getBytes("query_id")), row.getString("external_key"),
                        row.getInt("cutoff_k"), MetricStatus.valueOf(row.getString("metric_status")),
                        nullableDouble(row, "precision_value"), nullableDouble(row, "recall_value"),
                        nullableDouble(row, "f1_value"), nullableDouble(row, "mrr_value"), nullableDouble(row, "ndcg_value"),
                        row.getInt("relevant_count"), row.getInt("judged_count")), bytes(algorithmRunId));
    }

    List<RankedHitView> hits(UUID algorithmRunId) {
        return jdbc.query("SELECT * FROM evaluation_ranked_hits WHERE algorithm_run_id=? ORDER BY query_id,candidate_rank",
                (row, index) -> new RankedHitView(uuid(row.getBytes("query_id")), uuid(row.getBytes("study_id")),
                        row.getInt("candidate_rank"), row.getDouble("retrieval_score")), bytes(algorithmRunId));
    }

    ResourceUsageView resourceUsage(UUID algorithmRunId) {
        return jdbc.query("SELECT MAX(CASE WHEN sample_phase='BEFORE' THEN heap_used_bytes END) AS heap_before,"
                        + "MAX(heap_used_bytes) AS heap_peak,MAX(CASE WHEN sample_phase='AFTER' THEN heap_used_bytes END) AS heap_after,"
                        + "SUM(process_cpu_nanos) AS cpu_nanos,COUNT(*) AS sample_count FROM evaluation_resource_snapshots WHERE algorithm_run_id=?",
                (row, index) -> new ResourceUsageView(nullableLong(row, "heap_before"), nullableLong(row, "heap_peak"),
                        nullableLong(row, "heap_after"), nullableLong(row, "cpu_nanos"), row.getInt("sample_count")),
                bytes(algorithmRunId)).getFirst();
    }

    Map<String, String> databaseMetadata() {
        return jdbc.execute((ConnectionCallback<Map<String, String>>) connection -> Map.of(
                "product", connection.getMetaData().getDatabaseProductName(),
                "version", connection.getMetaData().getDatabaseProductVersion(),
                "driver", connection.getMetaData().getDriverName()));
    }

    private List<String> objectives(byte[] studyId) {
        return jdbc.query("SELECT statement_text FROM study_objectives WHERE study_id=? ORDER BY objective_order",
                (row, index) -> row.getString(1), studyId);
    }

    private List<String> keywords(byte[] studyId) {
        return jdbc.query("SELECT t.canonical_label FROM taxonomy_terms t JOIN study_terms s ON s.term_id=t.id WHERE s.study_id=? ORDER BY t.canonical_label",
                (row, index) -> row.getString(1), studyId);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static Algorithm algorithm(String code) {
        return java.util.Arrays.stream(Algorithm.values()).filter(value -> value.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown persisted evaluation algorithm: " + code));
    }
    private static Long nullableLong(java.sql.ResultSet row, String name) throws java.sql.SQLException {
        long value = row.getLong(name); return row.wasNull() ? null : value;
    }
    private static Double nullableDouble(java.sql.ResultSet row, String name) throws java.sql.SQLException {
        double value = row.getDouble(name); return row.wasNull() ? null : value;
    }
    private static String truncate(String value, int limit) {
        if (value == null) return null; return value.length() <= limit ? value : value.substring(0, limit);
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static byte[] nullableBytes(UUID id) { return id == null ? null : bytes(id); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { ByteBuffer value = ByteBuffer.wrap(bytes); return new UUID(value.getLong(), value.getLong()); }
}

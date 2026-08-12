package com.ugnay.platform.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public, persistence-oriented contracts for the research evaluation module. */
public final class EvaluationModels {
    private EvaluationModels() {}

    public static final List<Integer> CUTOFFS = List.of(1, 3, 5, 10);
    public static final int PRIMARY_K = 5;
    public static final int RELEVANCE_THRESHOLD = 1;

    public enum DatasetStatus { DRAFT, FROZEN }
    public enum QuerySplit { DEV, TEST }
    public enum RunStatus { QUEUED, RUNNING, COMPLETED, PARTIAL, UNAVAILABLE, FAILED }
    public enum MetricStatus { AVAILABLE, UNAVAILABLE }
    public enum ComparabilityStatus { COMPARABLE, PARTIAL, UNAVAILABLE }
    public enum ReportStatus { PRIVATE, PUBLISHED }

    public enum Algorithm {
        LEXICAL_KEYWORD("LEXICAL_KEYWORD_V1", "LEXICAL_KEYWORD_V1"),
        TF_IDF("TF_IDF_COSINE_V1", "TF_IDF_COSINE_V1"),
        SEMANTIC_E5("SEMANTIC_E5_V1", "SEMANTIC_E5_V1"),
        HYBRID("HYBRID_V1_1", "HYBRID_V1_1");

        private final String code;
        private final String version;

        Algorithm(String code, String version) {
            this.code = code;
            this.version = version;
        }

        public String code() { return code; }
        public String version() { return version; }
    }

    public record DatasetVersionView(
            UUID datasetId,
            UUID versionId,
            int version,
            String name,
            String description,
            DatasetStatus status,
            String corpusSha256,
            String datasetSha256,
            int corpusSize,
            int queryCount,
            int adjudicatedQrelCount,
            Instant createdAt,
            Instant frozenAt) {}

    public record QueryView(
            UUID id,
            UUID datasetVersionId,
            String externalKey,
            QuerySplit split,
            String title,
            String querySha256,
            int distinctReviewerCount,
            int adjudicatedQrelCount,
            Instant createdAt) {}

    public record JudgmentView(
            UUID id,
            UUID queryId,
            UUID studyId,
            String reviewer,
            int revision,
            int relevanceGrade,
            String rationale,
            Instant judgedAt) {}

    public record QrelView(
            UUID id,
            UUID queryId,
            UUID studyId,
            int revision,
            int relevanceGrade,
            String rationale,
            String adjudicatedBy,
            Instant adjudicatedAt) {}

    public record RunView(
            UUID id,
            UUID datasetVersionId,
            RunStatus status,
            ComparabilityStatus comparability,
            int primaryK,
            List<Integer> cutoffs,
            int repetitions,
            long executionSeed,
            String codeBuild,
            String environmentSha256,
            String runSha256,
            String failureReason,
            ReportStatus reportStatus,
            Instant queuedAt,
            Instant startedAt,
            Instant completedAt,
            Instant publishedAt) {}

    public record ActorJudgmentView(int relevanceGrade, String rationale, int revision, Instant judgedAt) {}

    public record CorpusReviewItem(
            UUID studyId,
            String title,
            String academicYear,
            String department,
            ActorJudgmentView currentActorJudgment,
            int distinctReviewerCount,
            boolean doubleReviewed,
            Integer adjudicatedGrade,
            boolean adjudicationCurrent) {}

    public record CorpusReviewPage(
            List<CorpusReviewItem> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    public record PublishedReportView(
            UUID runId,
            UUID datasetVersionId,
            String datasetName,
            String datasetSha256,
            RunStatus runStatus,
            ComparabilityStatus comparability,
            String codeBuild,
            Instant publishedAt) {}

    public record PublishedReportPage(
            List<PublishedReportView> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    public record AggregateMetricView(
            int k,
            MetricStatus status,
            Double precision,
            Double recall,
            Double f1,
            Double mrr,
            Double ndcg,
            int eligibleQueries,
            int excludedQueries) {}

    public record QueryMetricView(
            UUID queryId,
            String queryKey,
            int k,
            MetricStatus status,
            Double precision,
            Double recall,
            Double f1,
            Double mrr,
            Double ndcg,
            int relevantCount,
            int judgedCount) {}

    public record RankedHitView(UUID queryId, UUID studyId, int rank, double score) {}
    public record ResourceUsageView(Long heapBeforeBytes, Long heapPeakBytes, Long heapAfterBytes,
                                    Long processCpuNanos, int capturedSamples) {}

    public record AlgorithmReport(
            UUID algorithmRunId,
            Algorithm algorithm,
            String version,
            RunStatus status,
            String configurationSha256,
            String unavailableReason,
            Long indexBuildMillis,
            Double latencyP50Millis,
            Double latencyP95Millis,
            ResourceUsageView resourceUsage,
            List<AggregateMetricView> aggregateMetrics,
            List<QueryMetricView> queryMetrics,
            List<RankedHitView> rankedHits) {}

    public record EvaluationReport(
            RunView run,
            DatasetVersionView dataset,
            Map<String, Object> environment,
            Map<String, Object> manifest,
            List<AlgorithmReport> algorithms,
            String interpretationBoundary) {}
}

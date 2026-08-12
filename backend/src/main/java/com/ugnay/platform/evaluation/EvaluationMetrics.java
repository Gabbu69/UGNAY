package com.ugnay.platform.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ugnay.platform.evaluation.EvaluationModels.MetricStatus;
import static com.ugnay.platform.evaluation.EvaluationModels.RELEVANCE_THRESHOLD;

/** Exact, side-effect-free IR metric definitions used by every evaluation arm. */
public final class EvaluationMetrics {
    private EvaluationMetrics() {}

    public record QueryResult(
            int k,
            MetricStatus status,
            Double precision,
            Double recall,
            Double f1,
            Double mrr,
            Double ndcg,
            int relevantCount,
            int judgedCount) {}

    public record AggregateResult(
            int k,
            MetricStatus status,
            Double precision,
            Double recall,
            Double f1,
            Double mrr,
            Double ndcg,
            int eligibleQueries,
            int excludedQueries) {}

    public static QueryResult calculate(List<UUID> rankedStudyIds, Map<UUID, Integer> qrels, int k) {
        if (k < 1) throw new IllegalArgumentException("Metric cutoff K must be positive.");
        Map<UUID, Integer> safeQrels = qrels == null ? Map.of() : Map.copyOf(qrels);
        int relevantCount = (int) safeQrels.values().stream().filter(EvaluationMetrics::relevant).count();
        if (relevantCount == 0) {
            return new QueryResult(k, MetricStatus.UNAVAILABLE, null, null, null, null, null, 0, safeQrels.size());
        }

        List<UUID> ranking = new ArrayList<>(new LinkedHashSet<>(rankedStudyIds == null ? List.of() : rankedStudyIds));
        int relevantRetrieved = 0;
        double reciprocalRank = 0;
        double dcg = 0;
        for (int index = 0; index < Math.min(k, ranking.size()); index++) {
            int grade = safeQrels.getOrDefault(ranking.get(index), 0);
            if (relevant(grade)) {
                relevantRetrieved++;
                if (reciprocalRank == 0) reciprocalRank = 1.0 / (index + 1.0);
            }
            dcg += gain(grade) / log2(index + 2.0);
        }

        List<Integer> idealGrades = safeQrels.values().stream().sorted(Comparator.reverseOrder()).limit(k).toList();
        double idealDcg = 0;
        for (int index = 0; index < idealGrades.size(); index++) {
            idealDcg += gain(idealGrades.get(index)) / log2(index + 2.0);
        }
        double precision = relevantRetrieved / (double) k;
        double recall = relevantRetrieved / (double) relevantCount;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double ndcg = idealDcg == 0 ? 0 : dcg / idealDcg;
        return new QueryResult(k, MetricStatus.AVAILABLE, precision, recall, f1, reciprocalRank, ndcg,
                relevantCount, safeQrels.size());
    }

    public static AggregateResult aggregate(List<QueryResult> results, int k) {
        List<QueryResult> eligible = (results == null ? List.<QueryResult>of() : results).stream()
                .filter(result -> result.status() == MetricStatus.AVAILABLE).toList();
        int excluded = results == null ? 0 : results.size() - eligible.size();
        if (eligible.isEmpty()) {
            return new AggregateResult(k, MetricStatus.UNAVAILABLE, null, null, null, null, null, 0, excluded);
        }
        return new AggregateResult(k, MetricStatus.AVAILABLE,
                average(eligible, QueryResult::precision), average(eligible, QueryResult::recall),
                average(eligible, QueryResult::f1), average(eligible, QueryResult::mrr),
                average(eligible, QueryResult::ndcg), eligible.size(), excluded);
    }

    private static double average(List<QueryResult> values, java.util.function.Function<QueryResult, Double> extractor) {
        return values.stream().map(extractor).mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static boolean relevant(int grade) { return grade >= RELEVANCE_THRESHOLD; }
    private static double gain(int grade) { return Math.pow(2, Math.max(0, grade)) - 1; }
    private static double log2(double value) { return Math.log(value) / Math.log(2); }
}

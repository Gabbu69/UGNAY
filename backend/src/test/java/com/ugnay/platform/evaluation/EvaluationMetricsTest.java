package com.ugnay.platform.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationMetricsTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void calculatesBinaryAndGradedMetricsFromOneSharedRanking() {
        var result = EvaluationMetrics.calculate(List.of(A, B, C), Map.of(A, 3, C, 2, D, 1), 3);

        assertThat(result.status()).isEqualTo(EvaluationModels.MetricStatus.AVAILABLE);
        assertThat(result.precision()).isCloseTo(2.0 / 3, within());
        assertThat(result.recall()).isCloseTo(2.0 / 3, within());
        assertThat(result.f1()).isCloseTo(2.0 / 3, within());
        assertThat(result.mrr()).isEqualTo(1.0);
        double expected = (7 + 3 / log2(4)) / (7 + 3 / log2(3) + 1 / log2(4));
        assertThat(result.ndcg()).isCloseTo(expected, within());
        assertThat(result.relevantCount()).isEqualTo(3);
        assertThat(result.judgedCount()).isEqualTo(3);
    }

    @Test
    void treatsUnjudgedHitsAsNonRelevantAndUsesKAsPrecisionDenominator() {
        var result = EvaluationMetrics.calculate(List.of(B, A), Map.of(A, 1), 5);
        assertThat(result.precision()).isEqualTo(.2);
        assertThat(result.recall()).isEqualTo(1);
        assertThat(result.mrr()).isEqualTo(.5);
    }

    @Test
    void reportsNoPositiveGroundTruthAsUnavailableInsteadOfZero() {
        var result = EvaluationMetrics.calculate(List.of(A), Map.of(A, 0), 5);
        assertThat(result.status()).isEqualTo(EvaluationModels.MetricStatus.UNAVAILABLE);
        assertThat(result.precision()).isNull();
        assertThat(result.ndcg()).isNull();

        var aggregate = EvaluationMetrics.aggregate(List.of(result), 5);
        assertThat(aggregate.status()).isEqualTo(EvaluationModels.MetricStatus.UNAVAILABLE);
        assertThat(aggregate.eligibleQueries()).isZero();
        assertThat(aggregate.excludedQueries()).isOne();
    }

    @Test
    void deDuplicatesRepeatedStudyIdsWithoutChangingFirstRank() {
        var result = EvaluationMetrics.calculate(List.of(B, B, A), Map.of(A, 2), 3);
        assertThat(result.mrr()).isEqualTo(.5);
        assertThat(result.precision()).isCloseTo(1.0 / 3, within());
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-10);
    }

    private static double log2(double value) { return Math.log(value) / Math.log(2); }
}

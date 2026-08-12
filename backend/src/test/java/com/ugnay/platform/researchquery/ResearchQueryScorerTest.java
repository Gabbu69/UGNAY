package com.ugnay.platform.researchquery;

import com.ugnay.platform.discovery.EmbeddingProvider;
import com.ugnay.platform.researchquery.ResearchQueryRepository.PreparedPlan;
import com.ugnay.platform.researchquery.ResearchQueryRepository.StudyRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchQueryScorerTest {
    @Test
    void lexicalArmUsesDeterministicQueryTokenCoverageAndFiltersStrictYears() {
        ResearchQueryScorer scorer = new ResearchQueryScorer(new UnavailableEmbeddings());
        QueryPlan plan = plan("FIND THESIS WHERE YEAR >= 2022 USING LEXICAL");
        PreparedPlan prepared = new PreparedPlan(plan, "agriculture monitoring", null, null, null);
        StudyRecord strong = study("Agriculture monitoring platform", "2024", "agriculture, monitoring");
        StudyRecord weak = study("Library archive", "unknown", "documents");

        var result = scorer.score(prepared, List.of(weak, strong), System.nanoTime() + TimeUnit.SECONDS.toNanos(1));

        assertThat(result.assessmentStatus()).isEqualTo("ASSESSED");
        assertThat(result.studies()).singleElement().satisfies(value -> {
            assertThat(value.study().id()).isEqualTo(strong.id());
            assertThat(value.score()).isEqualTo(100.0);
        });
    }

    @Test
    void reportsSemanticUnavailableAndHybridPartialWithoutRescalingWeights() {
        ResearchQueryScorer scorer = new ResearchQueryScorer(new UnavailableEmbeddings());
        StudyRecord study = study("Flood warning", "2024", "flood, warning");

        var semantic = scorer.score(new PreparedPlan(plan("FIND RELATED TO TEXT \"flood warning\" USING SEMANTIC"),
                        "flood warning", null, null, "TEXT"), List.of(study), deadline());
        assertThat(semantic.assessmentStatus()).isEqualTo("UNAVAILABLE");
        assertThat(semantic.diagnostics()).extracting(QueryDiagnostic::code).contains("EXEC_SEMANTIC_UNAVAILABLE");

        var hybrid = scorer.score(new PreparedPlan(plan("FIND RELATED TO TEXT \"flood warning\" USING HYBRID"),
                        "flood warning", null, null, "TEXT"), List.of(study), deadline());
        assertThat(hybrid.assessmentStatus()).isEqualTo("PARTIAL");
        assertThat(hybrid.studies()).singleElement().satisfies(value -> {
            assertThat(value.score()).isPositive().isLessThanOrEqualTo(50.0);
            assertThat(value.explanations()).anyMatch(text -> text.contains("not rescaled"));
        });
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(1); }

    private static QueryPlan plan(String source) {
        var lexed = new ResearchQueryLexer().tokenize(source);
        var parsed = new ResearchQueryParser().parse(lexed.tokens());
        return new ResearchQuerySemanticValidator().validate(parsed.query(), null).plan();
    }

    private static StudyRecord study(String title, String year, String keywords) {
        Integer strictYear = year.matches("[0-9]{4}") ? Integer.valueOf(year) : null;
        return new StudyRecord(UUID.nameUUIDFromBytes(title.getBytes()), "TEST", title, year, strictYear,
                "CICS", "Computing", "PUBLISHED", "CAMPUS", title, title, List.of(title),
                List.of(keywords.split(", ")), List.of(), "Design science", "", "", "Java", "Students", "",
                "Campus", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final class UnavailableEmbeddings implements EmbeddingProvider {
        @Override public Optional<double[]> embed(String text) { return Optional.empty(); }
        @Override public String name() { return "test-local-model"; }
        @Override public String availabilityReason() { return "The test local model is unavailable."; }
        @Override public boolean available() { return false; }
    }
}

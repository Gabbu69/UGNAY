package com.ugnay.platform.discovery;

import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.Study;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityEngineTest {
    @Test
    void keepsUnavailableSemanticWeightAtZero() {
        SimilarityEngine engine = new SimilarityEngine(new MissingProvider());

        assertThat(engine.fieldScore("baha flood readiness", "flood baha readiness")).isEqualTo(50.0);
        assertThat(engine.semanticAvailable()).isFalse();
    }

    @Test
    void objectiveMatchingDoesNotReuseOnePriorObjective() {
        SimilarityEngine engine = new SimilarityEngine(new ConstantProvider());

        double overlap = engine.objectiveOverlap(
                List.of("Generate an offline flood plan", "Generate an offline flood plan"),
                List.of("Generate an offline flood plan"));

        assertThat(overlap).isEqualTo(50.0);
        assertThat(engine.objectiveNoveltyPercentage(
                List.of("Generate an offline flood plan", "Generate an offline flood plan"),
                List.of("Generate an offline flood plan"))).isEqualTo(50.0);
    }

    @Test
    void symmetricE5InputsUseQueryPrefixOnBothSides() {
        CapturingProvider provider = new CapturingProvider();
        SimilarityEngine engine = new SimilarityEngine(provider);

        engine.fieldScore("Campus flood readiness", "Household flood readiness");

        assertThat(provider.inputs).hasSize(2).allMatch(value -> value.startsWith("query: "));
        assertThat(provider.inputs).noneMatch(value -> value.startsWith("passage: "));
        assertThat(SimilarityEngine.e5Query("query: already prefixed")).isEqualTo("query: already prefixed");
    }

    @Test
    void boundsCandidatePoolsRerankingAndReturnedResultsWhileReusingStudyEmbeddings() {
        CountingProvider provider = new CountingProvider();
        SimilarityEngine engine = new SimilarityEngine(provider);
        List<Study> corpus = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            corpus.add(study("CIS-" + index, "Flood response evidence " + index,
                    "Barangay flood warning and offline response evidence group " + index));
        }
        Proposal proposal = proposal("Offline barangay flood response",
                "Communities need offline flood warning and response evidence during disconnection.");

        assertThat(engine.rank(proposal, corpus)).hasSize(SimilarityEngine.RESULT_LIMIT);
        SimilarityEngine.RetrievalDiagnostics first = engine.lastDiagnostics();
        assertThat(first.corpusSize()).isEqualTo(120);
        assertThat(first.lexicalPoolSize()).isLessThanOrEqualTo(SimilarityEngine.CANDIDATE_LIMIT);
        assertThat(first.semanticPoolSize()).isLessThanOrEqualTo(SimilarityEngine.CANDIDATE_LIMIT);
        assertThat(first.detailedRerankCount()).isLessThanOrEqualTo(100);
        assertThat(first.returnedCount()).isEqualTo(SimilarityEngine.RESULT_LIMIT);

        int firstEmbeddingCalls = provider.calls.get();
        engine.rank(proposal, corpus);
        assertThat(provider.calls.get() - firstEmbeddingCalls)
                .as("the unchanged corpus should reuse profile and field embeddings")
                .isLessThan(25);
    }

    @Test
    void exactInstitutionalIdentifierRemainsVisibleEvenWhenSimilarityIsWeak() {
        SimilarityEngine engine = new SimilarityEngine(new MissingProvider());
        List<Study> corpus = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            corpus.add(study("REL-" + index, "Offline flood response " + index,
                    "Offline flood response for barangay residents and local responders."));
        }
        Study exact = study("EXACT-2026-01", "Unrelated archival system", "Legacy filing workflow.");
        corpus.add(exact);

        var ranked = engine.rank(proposal("EXACT-2026-01",
                "Offline flood response for barangay residents and local responders."), corpus);

        assertThat(ranked).anySatisfy(candidate -> {
            assertThat(candidate.studyId()).isEqualTo(exact.id());
            assertThat(candidate.exactMatch()).isTrue();
        });
        assertThat(engine.lastDiagnostics().exactPoolSize()).isEqualTo(1);
    }

    private static Proposal proposal(String title, String problem) {
        return new Proposal(UUID.randomUUID(), title, problem, "Barangay responders", "Residents",
                "Rural campus community", "Faster verified coordination", "Intermittent connectivity", "INTERNAL",
                List.of("Evaluate offline flood-response coordination"), "Offline incident and warning workflow",
                "Design science and scenario evaluation", "Incident reports", "Java web application",
                "Residents and responders", "SUBMITTED", Instant.parse("2026-08-09T00:00:00Z"), 0);
    }

    private static Study study(String code, String title, String problem) {
        return new Study(UUID.randomUUID(), code, title, "2025-2026", "CIS", "COMPLETED", "CAMPUS",
                problem, problem, List.of("Evaluate offline flood-response coordination"),
                List.of("flood", "offline", "response"), "Design science and scenario evaluation",
                "Offline incident and warning workflow", "Incident reports", "Java web application",
                "Residents and responders", "Barangay responders", "Rural campus community", List.of());
    }

    private static final class MissingProvider implements EmbeddingProvider {
        @Override public Optional<double[]> embed(String text) { return Optional.empty(); }
        @Override public String name() { return "missing"; }
        @Override public String availabilityReason() { return "not configured"; }
    }

    private static class ConstantProvider implements EmbeddingProvider {
        @Override public Optional<double[]> embed(String text) { return Optional.of(new double[] { 1, 0, 0 }); }
        @Override public String name() { return "test"; }
        @Override public String availabilityReason() { return "available"; }
    }

    private static final class CapturingProvider extends ConstantProvider {
        private final List<String> inputs = new ArrayList<>();
        @Override public Optional<double[]> embed(String text) { inputs.add(text); return super.embed(text); }
    }

    private static final class CountingProvider extends ConstantProvider {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public Optional<double[]> embed(String text) {
            calls.incrementAndGet();
            int bucket = Math.floorMod(text.hashCode(), 7);
            return Optional.of(new double[] { 1, bucket / 10.0, (6 - bucket) / 10.0 });
        }
    }
}

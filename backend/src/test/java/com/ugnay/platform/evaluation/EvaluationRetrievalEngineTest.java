package com.ugnay.platform.evaluation;

import com.ugnay.platform.discovery.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRetrievalEngineTest {
    private static final String MODEL_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKENIZER_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private final UUID agriculture = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID hospital = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void lexicalAndTfIdfArmsRankDeterministically() {
        EvaluationRetrievalEngine engine = engine();
        var query = new EvaluationRetrievalEngine.QueryProfile(UUID.randomUUID(), "agriculture farmer price",
                Map.of("title", "Agriculture price", "problemStatement", "Farmer price access"));
        var studies = studies();

        var lexical = engine.evaluate(EvaluationModels.Algorithm.LEXICAL_KEYWORD, List.of(query), studies, 1);
        var tfIdf = engine.evaluate(EvaluationModels.Algorithm.TF_IDF, List.of(query), studies, 1);

        assertThat(lexical.status()).isEqualTo(EvaluationModels.RunStatus.COMPLETED);
        assertThat(tfIdf.status()).isEqualTo(EvaluationModels.RunStatus.COMPLETED);
        assertThat(lexical.rankings().get(query.id()).getFirst().studyId()).isEqualTo(agriculture);
        assertThat(tfIdf.rankings().get(query.id()).getFirst().studyId()).isEqualTo(agriculture);
    }

    @Test
    void semanticIsUnavailableAndHybridIsExplicitlyPartialWithoutLocalAssets() {
        EvaluationRetrievalEngine engine = engine();
        var query = new EvaluationRetrievalEngine.QueryProfile(UUID.randomUUID(), "baha campus",
                Map.of("title", "Baha readiness", "problemStatement", "Campus flooding"));

        var semantic = engine.evaluate(EvaluationModels.Algorithm.SEMANTIC_E5, List.of(query), studies(), 1);
        var hybrid = engine.evaluate(EvaluationModels.Algorithm.HYBRID, List.of(query), studies(), 1);

        assertThat(semantic.status()).isEqualTo(EvaluationModels.RunStatus.UNAVAILABLE);
        assertThat(semantic.rankings()).isEmpty();
        assertThat(semantic.unavailableReason()).contains("not configured");
        assertThat(hybrid.status()).isEqualTo(EvaluationModels.RunStatus.PARTIAL);
        assertThat(hybrid.rankings().get(query.id())).isNotEmpty();
    }

    @Test
    void tiesUseAscendingStudyUuid() {
        EvaluationRetrievalEngine engine = engine();
        UUID queryId = UUID.randomUUID();
        var outcome = engine.evaluate(EvaluationModels.Algorithm.LEXICAL_KEYWORD,
                List.of(new EvaluationRetrievalEngine.QueryProfile(queryId, "no overlap", Map.of())),
                studies().reversed(), 1);
        assertThat(outcome.rankings().get(queryId)).extracting(EvaluationRetrievalEngine.RankedHit::studyId)
                .containsExactly(agriculture, hospital);
    }

    @Test
    void semanticManifestRecordsConfiguredArtifactEvidenceWithoutPaths() {
        Map<String, Object> manifest = engine().semanticProviderManifest();

        assertThat(manifest)
                .containsEntry("modelVersion", "test-unavailable")
                .containsEntry("modelSha256", MODEL_SHA)
                .containsEntry("tokenizerSha256", TOKENIZER_SHA)
                .containsEntry("discoveryConfigurationVersion", "hybrid-test-v1")
                .containsEntry("execution", "LOCAL_ONLY")
                .containsEntry("remoteCalls", false);
        assertThat(manifest.keySet()).noneMatch(key -> key.toLowerCase().contains("path"));
    }

    private static EvaluationRetrievalEngine engine() {
        return new EvaluationRetrievalEngine(unavailableProvider(), MODEL_SHA, TOKENIZER_SHA, "hybrid-test-v1");
    }

    private List<EvaluationRetrievalEngine.StudyProfile> studies() {
        return List.of(
                new EvaluationRetrievalEngine.StudyProfile(agriculture, "agriculture farmer crop market price",
                        Map.of("title", "Agriculture market", "problemStatement", "Farmer crop pricing")),
                new EvaluationRetrievalEngine.StudyProfile(hospital, "hospital patient clinical records",
                        Map.of("title", "Hospital records", "problemStatement", "Patient workflow")));
    }

    private static EmbeddingProvider unavailableProvider() {
        return new EmbeddingProvider() {
            @Override public Optional<double[]> embed(String text) { return Optional.empty(); }
            @Override public String name() { return "test-unavailable"; }
            @Override public String availabilityReason() { return "Local semantic assets are not configured for this test."; }
            @Override public boolean available() { return false; }
        };
    }
}

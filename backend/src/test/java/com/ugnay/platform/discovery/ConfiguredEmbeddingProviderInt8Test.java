package com.ugnay.platform.discovery;

import com.ugnay.platform.shared.HeavyOperationCoordinator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredEmbeddingProviderInt8Test {
    @Test
    void releaseInt8AssetProducesTheExpectedEmbeddingShapeWhenProvided() throws Exception {
        String model = System.getProperty("ugnay.test.int8-model", "");
        String tokenizer = System.getProperty("ugnay.test.tokenizer", "");
        Assumptions.assumeTrue(!model.isBlank() && !tokenizer.isBlank(), "Release asset paths were not supplied.");
        try (var provider = new AutoClosingProvider(new ConfiguredEmbeddingProvider(model, sha(Path.of(model)),
                tokenizer, sha(Path.of(tokenizer)), 0, 30, new HeavyOperationCoordinator(1)))) {
            double[] vector = provider.delegate.embed("query: flood response continuity").orElseThrow();
            assertThat(vector).hasSize(384);
            double magnitude = Math.sqrt(java.util.Arrays.stream(vector).map(value -> value * value).sum());
            assertThat(magnitude).isBetween(0.999, 1.001);
        }
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record AutoClosingProvider(ConfiguredEmbeddingProvider delegate) implements AutoCloseable {
        @Override public void close() { delegate.close(); }
    }
}

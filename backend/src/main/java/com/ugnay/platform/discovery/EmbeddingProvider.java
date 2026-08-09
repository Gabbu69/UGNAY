package com.ugnay.platform.discovery;

import java.util.Optional;

/**
 * Boundary for local semantic models. Implementations must never call a remote
 * service: research text stays on the university-controlled host.
 */
public interface EmbeddingProvider {
    Optional<double[]> embed(String text);
    String name();
    String availabilityReason();

    default boolean available() {
        return embed("ugnay-provider-probe").isPresent();
    }
}

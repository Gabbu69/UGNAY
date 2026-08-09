package com.ugnay.platform.continuity;

import com.ugnay.platform.shared.PlatformModels.LineageEdge;
import com.ugnay.platform.shared.PlatformModels.LineageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LineageValidatorTest {
    @Test
    void rejectsSelfLinksAndAncestorCyclesButAllowsForks() {
        UUID origin = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID successor = UUID.randomUUID();
        List<LineageEdge> edges = List.of(new LineageEdge(UUID.randomUUID(), origin, child,
                LineageType.CONTINUES, true, "test"));
        LineageValidator validator = new LineageValidator();

        assertThat(validator.wouldCreateCycle(edges, origin, origin)).isTrue();
        assertThat(validator.wouldCreateCycle(edges, child, origin)).isTrue();
        assertThat(validator.wouldCreateCycle(edges, origin, successor)).isFalse();
    }
}

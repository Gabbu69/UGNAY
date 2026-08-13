package com.ugnay.platform.shared;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HeavyOperationCoordinatorTest {
    @Test
    void litePermitExcludesConcurrentHeavyWorkAndIsReleasedExactlyOnce() throws Exception {
        HeavyOperationCoordinator coordinator = new HeavyOperationCoordinator(1);

        var first = coordinator.tryAcquire("EVALUATION").orElseThrow();
        assertThat(coordinator.busy()).isTrue();
        try (var nested = coordinator.tryAcquire("ONNX_INFERENCE").orElseThrow()) {
            assertThat(nested.operation()).isEqualTo("ONNX_INFERENCE");
            assertThat(coordinator.busy()).isTrue();
        }
        AtomicBoolean excluded = new AtomicBoolean();
        Thread competing = new Thread(() -> excluded.set(coordinator.tryAcquire("WAREHOUSE").isEmpty()));
        competing.start();
        competing.join();
        assertThat(excluded).isTrue();

        first.close();
        first.close();

        try (var second = coordinator.tryAcquire("WAREHOUSE").orElseThrow()) {
            assertThat(second.operation()).isEqualTo("WAREHOUSE");
            assertThat(coordinator.busy()).isTrue();
        }
        assertThat(coordinator.busy()).isFalse();
    }
}

package com.ugnay.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTest {
    @Test
    void modulesHaveNoCyclesOrIllegalInternalDependencies() {
        ApplicationModules.of(UgnayApplication.class).verify();
    }
}

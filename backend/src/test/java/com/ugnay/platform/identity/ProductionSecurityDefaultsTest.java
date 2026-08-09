package com.ugnay.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecurityDefaultsTest {
    @Test
    void productionDisablesAnonymousDemoReadsUnlessExplicitlyEnabled() throws Exception {
        String profile = new ClassPathResource("application-prod.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(profile).contains("public-demo-read: ${UGNAY_PUBLIC_DEMO_READ:false}");
    }
}

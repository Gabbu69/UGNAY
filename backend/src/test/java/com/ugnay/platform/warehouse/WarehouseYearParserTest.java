package com.ugnay.platform.warehouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseYearParserTest {
    @Test
    void derivesOnlyStrictCompletionYears() {
        assertThat(WarehouseYearParser.completionYear("2025")).hasValue(2025);
        assertThat(WarehouseYearParser.completionYear("2025-2026")).hasValue(2026);
        assertThat(WarehouseYearParser.completionYear(" 2025-2026 ")).hasValue(2026);

        assertThat(WarehouseYearParser.completionYear(null)).isEmpty();
        assertThat(WarehouseYearParser.completionYear("2025/2026")).isEmpty();
        assertThat(WarehouseYearParser.completionYear("2025-2027")).isEmpty();
        assertThat(WarehouseYearParser.completionYear("25-26")).isEmpty();
        assertThat(WarehouseYearParser.completionYear("2201")).isEmpty();
    }
}

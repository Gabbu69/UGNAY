package com.ugnay.platform.catalogue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueSearchServiceTest {

    @Test
    void pageWindowCapsExtremePagesWithoutOverflowingTheSqlOffset() {
        CatalogueSearchService.PageWindow window = CatalogueSearchService.pageWindow(Integer.MAX_VALUE, 100);

        assertThat(window.page()).isEqualTo(Integer.MAX_VALUE / 100);
        assertThat(window.size()).isEqualTo(100);
        assertThat(window.offset()).isEqualTo((Integer.MAX_VALUE / 100) * 100);
        assertThat(window.offset()).isNotNegative();
    }

    @Test
    void pageWindowNormalizesNegativePagesAndInvalidSizes() {
        assertThat(CatalogueSearchService.pageWindow(-1, 0))
                .isEqualTo(new CatalogueSearchService.PageWindow(0, 1, 0));
    }
}

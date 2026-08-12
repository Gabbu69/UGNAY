package com.ugnay.platform.catalogue;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalogue")
@PreAuthorize("isAuthenticated()")
public class CatalogueSearchController {
    private final CatalogueSearchService catalogue;

    public CatalogueSearchController(CatalogueSearchService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/search")
    public CatalogueSearchService.SearchPage search(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "YEAR_DESC") String sort) {
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
            throw new IllegalArgumentException("yearFrom cannot be greater than yearTo.");
        }
        return catalogue.search(authentication, new CatalogueSearchService.SearchFilter(q, department, yearFrom, yearTo,
                lifecycle, topic, page, size, sort));
    }
}

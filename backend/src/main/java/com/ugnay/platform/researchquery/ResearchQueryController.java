package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.ResearchQueryContracts.ExecuteRequest;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ExecuteResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/research-queries")
@PreAuthorize("isAuthenticated()")
public class ResearchQueryController {
    private final ResearchQueryService service;

    public ResearchQueryController(ResearchQueryService service) {
        this.service = service;
    }

    @GetMapping("/grammar")
    public ResearchQueryLanguage.GrammarDescription grammar() {
        return ResearchQueryLanguage.grammar();
    }

    @PostMapping("/execute")
    public ExecuteResponse execute(@RequestBody ExecuteRequest request, Authentication authentication) {
        return service.execute(request, authentication);
    }
}

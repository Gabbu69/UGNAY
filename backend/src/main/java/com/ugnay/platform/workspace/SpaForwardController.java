package com.ugnay.platform.workspace;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Lets browser refreshes on the React application's named spaces reach index.html. */
@Controller
public final class SpaForwardController {
    @GetMapping({"/atlas", "/intake", "/decision", "/alignment", "/changes", "/continuity", "/reviews",
            "/research-lab/query", "/research-lab/evaluation", "/research-lab/warehouse"})
    public String forwardNamedWorkspace() {
        return "forward:/index.html";
    }
}

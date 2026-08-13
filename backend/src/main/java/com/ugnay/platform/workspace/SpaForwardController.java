package com.ugnay.platform.workspace;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Lets browser refreshes on the React application's named spaces reach index.html. */
@Controller
public final class SpaForwardController {
    @GetMapping({"/atlas", "/intake", "/decision", "/alignment", "/changes", "/continuity", "/reviews",
            "/research-lab/query", "/research-lab/evaluation", "/research-lab/warehouse",
            "/projects/{projectId}/alignment", "/projects/{projectId}/changes",
            "/projects/{projectId}/continuity", "/projects/{projectId}/reviews"})
    public String forwardNamedWorkspace() {
        return "forward:/index.html";
    }
}

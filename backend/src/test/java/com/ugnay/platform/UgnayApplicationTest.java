package com.ugnay.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.workspace.WorkspaceService;
import jakarta.servlet.http.Cookie;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UgnayApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcAuditService audit;
    @Autowired WorkspaceService workspace;

    @Test
    void publicPilotWorkspaceMatchesFrontendContract() throws Exception {
        mvc.perform(get("/api/v1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUser.name").value("Dr. Amara Reyes"))
                .andExpect(jsonPath("$.project.code").value("UGNAY-26-014"))
                .andExpect(jsonPath("$.studies[0].abstract").isString())
                .andExpect(jsonPath("$.traceNodes").isNotEmpty())
                .andExpect(jsonPath("$.findings").isNotEmpty())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCannotReadAccountOrAuditAdministration() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/invitations")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CURATOR")
    void curatorCanReadDatabaseBackedAccountAdministration() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("admin@ugnay.local"));
        mvc.perform(get("/api/v1/invitations")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-events")).andExpect(status().isOk()).andExpect(jsonPath("$[0].action").exists());
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void curatorCannotCreateTwoActiveInvitationsForOneEmail() throws Exception {
        String invitation = "{\"email\":\"pilot.student@ugnay.local\",\"role\":\"STUDENT\"}";
        mvc.perform(post("/api/v1/invitations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invitation))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.oneTimeToken").isNotEmpty());
        mvc.perform(post("/api/v1/invitations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invitation))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void databaseSeededBootstrapAccountCanCreateASession() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@ugnay.local\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.roles").isNotEmpty());
    }

    @Test
    void loginRotatesAnExistingAnonymousSessionId() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymousSession = csrfResult.getResponse().getCookie("SESSION");
        assertThat(anonymousSession).isNotNull();

        MvcResult result = mvc.perform(post("/api/v1/auth/login").cookie(anonymousSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@ugnay.local\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn();

        Cookie authenticatedSession = result.getResponse().getCookie("SESSION");
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getValue()).isNotEqualTo(anonymousSession.getValue());
    }

    @Test
    void namedSpaSpacesForwardWithoutAuthentication() throws Exception {
        mvc.perform(get("/atlas")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/index.html"));
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void workspaceCurrentUserComesFromTheAuthenticatedAccount() throws Exception {
        mvc.perform(get("/api/v1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUser.name").value("UGNAY Pilot Administrator"))
                .andExpect(jsonPath("$.currentUser.roles").isArray());
    }

    @Test
    void restrictedStudyDetailIsRedactedForAnonymousCatalogueReaders() throws Exception {
        String restrictedId = com.ugnay.platform.workspace.WorkspaceService.id("study-hospital").toString();
        mvc.perform(get("/api/v1/studies/" + restrictedId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.abstractText").value("Restricted catalogue record."))
                .andExpect(jsonPath("$.problemStatement").value("Protected"))
                .andExpect(jsonPath("$.continuationItems").isEmpty());
    }

    @Test
    @WithMockUser(roles = "CURATOR")
    void curatorCanReadRestrictedStudyDetail() throws Exception {
        String restrictedId = com.ugnay.platform.workspace.WorkspaceService.id("study-hospital").toString();
        mvc.perform(get("/api/v1/studies/" + restrictedId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.abstractText").value(org.hamcrest.Matchers.containsString("hospital workflow")));
    }

    @Test
    void restrictedDiscoveryEvidenceIsRedactedOnTheWire() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/discovery-runs")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("Restricted evidence excerpt.")
                .doesNotContain("Clinical units need controlled access to patient information");
    }

    @Test
    @WithMockUser(roles = "CURATOR")
    void curatorRoleAloneCannotSubmitStudentAcademicIntake() throws Exception {
        mvc.perform(post("/api/v1/problems").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Blocked\",\"problemStatement\":\"This valid-length problem statement must still be rejected by role authorization.\",\"stakeholder\":\"Office\",\"affectedUsers\":\"Students\",\"siteContext\":\"Campus\",\"desiredOutcome\":\"Outcome\",\"privacyClassification\":\"INTERNAL\",\"evidenceCount\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void baselineBoundImpactPreviewRequiresExactProjectEtag() throws Exception {
        String changeId = com.ugnay.platform.workspace.WorkspaceService.id("change-route-alert").toString();
        mvc.perform(post("/api/v1/change-requests/" + changeId + "/preview-impact").with(csrf()))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/v1/change-requests/" + changeId + "/preview-impact").with(csrf()).header("If-Match", "\"6\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/change-requests/" + changeId + "/preview-impact").with(csrf()).header("If-Match", "\"7\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineCurrent").value(true));
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "COORDINATOR")
    void completionKeepsProjectOpenWhenHardGatesFail() throws Exception {
        String projectId = com.ugnay.platform.workspace.WorkspaceService.id("project-campus-flood").toString();
        mvc.perform(post("/api/v1/projects/" + projectId + "/complete").with(csrf()))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/v1/projects/" + projectId + "/complete").with(csrf()).header("If-Match", "\"7\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockers").isNotEmpty());
        assertThat(audit.list(500)).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("COMPLETION_GATES_EVALUATED");
            assertThat(event.subjectId()).hasToString(projectId);
            assertThat(event.actorEmail()).isEqualTo("admin@ugnay.local");
        });
        mvc.perform(get("/api/v1/projects/" + projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALIDATING"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void discoveryPayloadMatchesFrontendContractAndDisclosesPartialFallback() throws Exception {
        String input = """
                {"title":"Campus flood readiness","problemStatement":"Campus buildings lack an offline flood readiness plan and evidence trail.",
                 "objectives":["Assess building flood readiness","Generate an offline response plan"],
                 "stakeholders":["Disaster risk office","Students"],"siteContext":"Flood-prone campus buildings",
                 "domainTerms":["baha","flood","offline","preparedness"]}
                """;
        mvc.perform(post("/api/v1/discovery-runs").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.recommendation").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.candidates[0].problemSimilarity").isNumber())
                .andExpect(jsonPath("$.algorithmVersion").value("hybrid-v1.0.0"));
    }

    @Test
    void publicDemoDiscoveryStillRequiresIssuedCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/discovery-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Demo\",\"problemStatement\":\"Evidence-backed demo problem\",\"objectives\":[\"Assess the problem\"],\"stakeholders\":[\"Students\"],\"siteContext\":\"Campus\",\"domainTerms\":[\"research\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void findingLifecycleRequiresRoleExpiryAndCurrentProjectEtag() throws Exception {
        String title = "Finding lifecycle " + java.util.UUID.randomUUID();
        var problem = workspace.createProblem(title, "Research evidence has no durable reviewer disposition and recurrence history.",
                "Research coordinator", "Student researchers", "CICS", "Preserve reviewer decisions", "Internal pilot",
                "INTERNAL", 1);
        var proposal = workspace.createProposal(problem.id(), title, java.util.List.of("Preserve reviewer finding dispositions"),
                "Build an evidence disposition workflow", "Design science", "Trace evidence", "Java and MySQL", "Reviewers");
        var discovery = workspace.runDiscovery(proposal.id());
        workspace.decide(proposal.id(), discovery.id(), com.ugnay.platform.shared.PlatformModels.DecisionDisposition.APPROVE_NEW,
                "This distinct durable governance workflow justifies a focused new pilot project.", null);
        var project = workspace.projects().stream().filter(value -> value.title().equals(title)).findFirst().orElseThrow();
        var finding = workspace.traceability(project.id()).findings().getFirst();
        String route = "/api/v1/projects/" + project.id() + "/findings/" + finding.id();
        String etag = "\"" + workspace.project(project.id()).rowVersion() + "\"";

        mvc.perform(post(route + "/resolve").with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin@ugnay.local").roles("ADVISER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Evidence now satisfies the rule.\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post(route + "/accept").with(csrf()).header("If-Match", etag)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin@ugnay.local").roles("ADVISER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Temporary exception has academic justification.\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(route + "/accept").with(csrf()).header("If-Match", etag)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin@ugnay.local").roles("COORDINATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Temporary exception has academic justification.\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(route + "/resolve").with(csrf()).header("If-Match", etag)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin@ugnay.local").roles("ADVISER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Evidence now satisfies the detected condition.\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (project.rowVersion() + 1) + "\""))
                .andExpect(jsonPath("$.action.state").value("RESOLVED"));
    }
}

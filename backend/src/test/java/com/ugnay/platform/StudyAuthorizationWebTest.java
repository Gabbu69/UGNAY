package com.ugnay.platform;

import com.ugnay.platform.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudyAuthorizationWebTest {
    private static final UUID RESTRICTED = WorkspaceService.id("study-hospital");
    private static final UUID CAMPUS = WorkspaceService.id("study-flood");
    private static final UUID PROPOSAL = WorkspaceService.id("proposal-campus-flood");
    private static final UUID PROJECT = WorkspaceService.id("project-campus-flood");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void legacyStudyReadsAndRouteEvidenceRequireAuthenticationEvenInPublicDemoMode() throws Exception {
        mvc.perform(get("/api/v1/studies")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/studies/{id}", CAMPUS)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, CAMPUS))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonCuratorNeverReceivesRestrictedStudyIdentityOrText() throws Exception {
        String legacy = mvc.perform(get("/api/v1/studies")
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + RESTRICTED + "')]").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String catalogue = mvc.perform(get("/api/v1/catalogue/search").param("size", "100")
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + RESTRICTED + "')]").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(legacy).doesNotContain("CICS-2023-032", "USM Hospital Operations Information System",
                "Clinical units need controlled access");
        assertThat(catalogue).doesNotContain("CICS-2023-032", "USM Hospital Operations Information System",
                "Clinical units need controlled access");
        mvc.perform(get("/api/v1/studies/{id}", RESTRICTED)
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void campusStudiesRemainGloballyReadableToAuthenticatedAccounts() throws Exception {
        mvc.perform(get("/api/v1/studies/{id}", CAMPUS)
                        .with(user("reader@external.example").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionalCode").value("CICS-2024-018"));
        mvc.perform(get("/api/v1/catalogue/search").param("q", "LIGTAS")
                        .with(user("reader@external.example").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(CAMPUS.toString()));
    }

    @Test
    void curatorCanReadRestrictedDetailCatalogueRowAndRouteEvidence() throws Exception {
        mvc.perform(get("/api/v1/studies/{id}", RESTRICTED)
                        .with(user("curator@example.test").roles("CURATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionalCode").value("CICS-2023-032"))
                .andExpect(jsonPath("$.abstractText").value(org.hamcrest.Matchers.containsString("hospital workflow")));
        mvc.perform(get("/api/v1/catalogue/search").param("q", "Hospital")
                        .with(user("curator@example.test").roles("CURATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(RESTRICTED.toString()));
        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, RESTRICTED)
                        .with(user("curator@example.test").roles("CURATOR")))
                .andExpect(status().isOk());
    }

    @Test
    void projectMemberCanReadPermittedRouteEvidenceButRestrictedPredecessorStillFails() throws Exception {
        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, CAMPUS)
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, RESTRICTED)
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameDepartmentAccountWithoutProjectMembershipCannotReadRouteEvidence() throws Exception {
        UUID account = UUID.randomUUID();
        byte[] cics = jdbc.queryForObject("SELECT id FROM departments WHERE code='CICS'", byte[].class);
        insertAccount(account, cics, "same-department-outsider@example.test");

        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, CAMPUS)
                        .with(user("same-department-outsider@example.test").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossDepartmentMembershipCannotBypassProposalDepartmentPolicy() throws Exception {
        UUID department = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,active,row_version,created_at) VALUES(?,?,?,?,?,?)",
                bytes(department), "ENG-" + department.toString().substring(0, 6), "College of Engineering", true, 0,
                Timestamp.from(Instant.now()));
        insertAccount(account, bytes(department), "cross-department@example.test");
        jdbc.update("INSERT INTO project_memberships(project_id,user_id,membership_role,joined_at) VALUES(?,?,?,?)",
                bytes(PROJECT), bytes(account), "STUDENT", Timestamp.from(Instant.now()));

        mvc.perform(get("/api/v1/proposals/{id}/route-evidence/{predecessorId}", PROPOSAL, CAMPUS)
                        .with(user("cross-department@example.test").roles("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void lineageCheckRequiresMembershipAndDoesNotRevealWhetherAProjectExists() throws Exception {
        UUID outsiderId = UUID.randomUUID();
        byte[] cics = jdbc.queryForObject("SELECT id FROM departments WHERE code='CICS'", byte[].class);
        String outsiderEmail = "lineage-outsider-" + outsiderId + "@example.test";
        insertAccount(outsiderId, cics, outsiderEmail);

        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String knownProject = lineageCheck(PROJECT, source, target);
        String unknownProject = lineageCheck(UUID.randomUUID(), source, target);
        var knownResponse = mvc.perform(post("/api/v1/lineage/check").with(csrf())
                        .with(user(outsiderEmail).roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(knownProject))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        var unknownResponse = mvc.perform(post("/api/v1/lineage/check").with(csrf())
                        .with(user(outsiderEmail).roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(unknownProject))
                .andExpect(status().isForbidden()).andReturn().getResponse();

        assertThat(knownResponse.getContentAsString()).isEqualTo(unknownResponse.getContentAsString());
        mvc.perform(post("/api/v1/lineage/check").with(csrf())
                        .with(user("admin@ugnay.local").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(knownProject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").isBoolean())
                .andExpect(jsonPath("$.wouldCreateCycle").isBoolean());
    }

    private void insertAccount(UUID id, byte[] department, String email) {
        jdbc.update("INSERT INTO user_accounts(id,department_id,email,display_name,account_status,row_version,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                bytes(id), department, email, "Authorization test account", "ACTIVE", 0, Timestamp.from(Instant.now()));
    }

    private static String lineageCheck(UUID projectId, UUID sourceId, UUID targetId) {
        return "{\"projectId\":\"" + projectId + "\",\"sourceId\":\"" + sourceId
                + "\",\"targetId\":\"" + targetId + "\",\"lineageType\":\"CONTINUES\"}";
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

package com.ugnay.platform.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-review-workflow-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchReviewWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired WorkspaceService workspace;
    @Autowired JdbcTemplate jdbc;

    @Test
    void revisionRequestAndResponseAreProjectScopedActorAttributedAndAppendOnly() throws Exception {
        var project = workspace.projects().stream().filter(value -> !workspace.researchReviews(value.id()).isEmpty())
                .findFirst().orElseThrow();
        var review = workspace.researchReviews(project.id()).stream()
                .filter(value -> "ADVISER".equals(value.requiredRole())).findFirst().orElseThrow();
        assertThat(review.history()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_review_events WHERE review_id=?", Integer.class,
                bytes(review.id()))).isZero();
        String requestRoute = "/api/v1/projects/" + project.id() + "/reviews/" + review.id() + "/revision-requests";
        String responseRoute = "/api/v1/projects/" + project.id() + "/reviews/" + review.id() + "/revision-responses";
        String requestBody = "{\"message\":\"Clarify the missing trace rationale and attach the revised evidence output.\",\"evidenceLocation\":\"trace://finding/" + review.id() + "\"}";

        mvc.perform(post(requestRoute).with(user("admin@ugnay.local").roles("ADVISER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isPreconditionRequired());

        long version = workspace.project(project.id()).rowVersion();
        mvc.perform(post(requestRoute).with(user("admin@ugnay.local").roles("ADVISER")).with(csrf())
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"" + (version + 1) + "\""))
                .andExpect(jsonPath("$.review.status").value("REVISION_REQUESTED"))
                .andExpect(jsonPath("$.review.history[-1].actorEmail").value("admin@ugnay.local"));

        String responseBody = "{\"message\":\"The revised trace rationale and linked evidence output are now recorded for re-review.\",\"evidenceLocation\":\"trace://revision/" + review.id() + "\"}";
        mvc.perform(post(responseRoute).with(user("admin@ugnay.local").roles("STUDENT")).with(csrf())
                        .header("If-Match", "\"" + (version + 1) + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(responseBody))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"" + (version + 2) + "\""))
                .andExpect(jsonPath("$.review.status").value("REVISION_RESPONDED"));

        mvc.perform(get("/api/v1/projects/" + project.id() + "/reviews")
                        .with(user("admin@ugnay.local").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + review.id() + "')].history.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_review_events WHERE review_id=?", Integer.class,
                bytes(review.id()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE subject_id=? AND action_code IN ('REVISION_REQUESTED','REVISION_RESPONDED')",
                Integer.class, bytes(review.id()))).isEqualTo(2);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

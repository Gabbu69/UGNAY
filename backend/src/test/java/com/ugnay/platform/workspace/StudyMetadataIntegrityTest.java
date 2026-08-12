package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.Study;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ugnay-study-metadata-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR")
@ActiveProfiles("test")
class StudyMetadataIntegrityTest {
    @Autowired WorkspaceService workspace;
    @Autowired CatalogueMetadataRepository metadata;
    @Autowired JdbcTemplate jdbc;

    @Test
    void blankOptionalMetadataStaysUnavailableAndUsesConservativeStates() {
        String code = "MISSING-" + UUID.randomUUID();
        Study imported = workspace.importStudy(code, "Study with unavailable historical metadata", " ",
                "Reviewed abstract evidence.", "Reviewed problem evidence.", List.of("Preserve evidence"),
                List.of("continuity"), "Document review", "Historical catalogue entry", "Researchers", "Campus",
                " ", null, null, null, null, null, "admin@ugnay.local");
        metadata.recordReviewedEvidence(imported.id(), new CatalogueMetadataRepository.ReviewedEvidence(
                " ", " ", null, null, null, null, List.of(), null, null),
                "admin@ugnay.local", "CURATOR_REVIEW");

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT department_id,academic_year,completion_year,visibility,lifecycle_status FROM studies WHERE institutional_code=?",
                code);
        assertThat(stored.get("department_id")).isNull();
        assertThat(stored.get("academic_year")).isNull();
        assertThat(stored.get("completion_year")).isNull();
        assertThat(stored.get("visibility")).isEqualTo("RESTRICTED");
        assertThat(stored.get("lifecycle_status")).isEqualTo("INCOMPLETE");

        assertThat(workspace.studies()).extracting(Study::id).contains(imported.id());
        assertThat(workspace.studies().getLast().id()).isEqualTo(imported.id());
    }
}

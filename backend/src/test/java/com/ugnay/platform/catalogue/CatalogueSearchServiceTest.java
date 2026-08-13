package com.ugnay.platform.catalogue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import com.ugnay.platform.identity.StudyVisibilityPolicy;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void textSearchTreatsLikeMetacharactersAndBackslashAsLiteralTextInH2() {
        JdbcTemplate jdbc = catalogueDatabase();
        UUID matchingStudy = UUID.randomUUID();
        insertStudy(jdbc, matchingStudy, "Literal 100%_safe!path\\leaf");
        insertStudy(jdbc, UUID.randomUUID(), "Literal 100 percent safe path leaf");
        CatalogueSearchService catalogue = new CatalogueSearchService(jdbc, new StudyVisibilityPolicy(jdbc));
        var curator = UsernamePasswordAuthenticationToken.authenticated("curator@example.test", "",
                AuthorityUtils.createAuthorityList("ROLE_CURATOR"));

        for (Map.Entry<String, String> specialCharacter : Map.of(
                "percent", "%",
                "underscore", "_",
                "escape character", "!",
                "backslash", "\\").entrySet()) {
            CatalogueSearchService.SearchPage result = catalogue.search(curator,
                    new CatalogueSearchService.SearchFilter(specialCharacter.getValue(), null, null, null,
                            null, null, 0, 20, "TITLE_ASC"));

            assertThat(result.items())
                    .as("literal search for %s", specialCharacter.getKey())
                    .extracting(CatalogueSearchService.StudySummary::id)
                    .containsExactly(matchingStudy);
        }
    }

    private static JdbcTemplate catalogueDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build());
        jdbc.execute("CREATE TABLE departments (id BINARY(16) PRIMARY KEY, code VARCHAR(32), name VARCHAR(255))");
        jdbc.execute("CREATE TABLE studies (id BINARY(16) PRIMARY KEY, institutional_code VARCHAR(64), "
                + "title VARCHAR(500), academic_year VARCHAR(32), completion_year INT, department_id BINARY(16), "
                + "program_name VARCHAR(255), lifecycle_status VARCHAR(32), visibility VARCHAR(32), "
                + "abstract_text CLOB, problem_statement CLOB, methodology CLOB, keywords_text CLOB, "
                + "results_text CLOB, archived_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE study_objectives (study_id BINARY(16))");
        return jdbc;
    }

    private static void insertStudy(JdbcTemplate jdbc, UUID id, String title) {
        jdbc.update("INSERT INTO studies(id,institutional_code,title,academic_year,completion_year,"
                        + "program_name,lifecycle_status,visibility,abstract_text,problem_statement,methodology,"
                        + "keywords_text,results_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(id), "TEST-" + id.toString().substring(0, 8), title, "2025-2026", 2026,
                "BSCS", "COMPLETED", "PUBLIC", "", "", "", "", "");
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

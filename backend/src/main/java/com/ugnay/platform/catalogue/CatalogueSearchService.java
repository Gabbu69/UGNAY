package com.ugnay.platform.catalogue;

import com.ugnay.platform.identity.StudyVisibilityPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization-aware, bounded catalogue projection for web search.
 *
 * <p>This projection deliberately does not borrow scores from a discovery run.
 * It returns only facts from the catalogue row that matched this request.</p>
 */
@Service
public class CatalogueSearchService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTS = Set.of("YEAR_DESC", "YEAR_ASC", "TITLE_ASC", "TITLE_DESC");

    private final JdbcTemplate jdbc;
    private final StudyVisibilityPolicy visibility;

    public CatalogueSearchService(JdbcTemplate jdbc, StudyVisibilityPolicy visibility) {
        this.jdbc = jdbc;
        this.visibility = visibility;
    }

    public SearchPage search(Authentication authentication, SearchFilter filter) {
        PageWindow window = pageWindow(filter.page(), filter.size());
        int page = window.page();
        int size = window.size();
        String sort = SORTS.contains(normalize(filter.sort())) ? normalize(filter.sort()) : "YEAR_DESC";

        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE s.archived_at IS NULL ");
        StudyVisibilityPolicy.SqlRestriction authorization = visibility.studyTableRestriction(visibility.scope(authentication));
        where.append(authorization.clause());
        parameters.addAll(authorization.parameters());
        addTextSearch(where, parameters, filter.query());
        addEquals(where, parameters, "d.code", filter.department());
        addEquals(where, parameters, "s.lifecycle_status", filter.lifecycle());
        if (filter.yearFrom() != null) {
            where.append(" AND s.completion_year >= ? ");
            parameters.add(filter.yearFrom());
        }
        if (filter.yearTo() != null) {
            where.append(" AND s.completion_year <= ? ");
            parameters.add(filter.yearTo());
        }
        if (filter.topic() != null && !filter.topic().isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM study_terms st JOIN taxonomy_terms tt ON tt.id=st.term_id")
                    .append(" WHERE st.study_id=s.id AND tt.active=TRUE AND tt.term_type IN ('KEYWORD','RESEARCH_AREA')")
                    .append(" AND LOWER(tt.canonical_label)=LOWER(?)) ");
            parameters.add(filter.topic().strip());
        }

        String from = " FROM studies s LEFT JOIN departments d ON d.id=s.department_id ";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(window.offset());
        String sql = "SELECT s.id, s.institutional_code, s.title, s.academic_year, s.completion_year," +
                " d.code AS department_code, d.name AS department_name, s.program_name, s.lifecycle_status," +
                " s.visibility, s.abstract_text, s.problem_statement, s.methodology, s.keywords_text, s.results_text," +
                " (SELECT COUNT(*) FROM study_objectives so WHERE so.study_id=s.id) AS objective_count" +
                from + where + orderBy(sort) + " LIMIT ? OFFSET ?";
        List<StudySummary> items = jdbc.query(sql, this::mapStudy, pageParameters.toArray());
        return new SearchPage(List.copyOf(items), total == null ? 0 : total, page, size, Instant.now());
    }

    private static void addTextSearch(StringBuilder where, List<Object> parameters, String query) {
        if (query == null || query.isBlank()) return;
        String pattern = "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
        where.append(" AND (LOWER(s.title) LIKE ? ESCAPE '!'")
                .append(" OR LOWER(COALESCE(s.abstract_text,'')) LIKE ? ESCAPE '!'")
                .append(" OR LOWER(COALESCE(s.problem_statement,'')) LIKE ? ESCAPE '!'")
                .append(" OR LOWER(COALESCE(s.methodology,'')) LIKE ? ESCAPE '!'")
                .append(" OR LOWER(COALESCE(s.keywords_text,'')) LIKE ? ESCAPE '!') ");
        for (int index = 0; index < 5; index++) parameters.add(pattern);
    }

    private static void addEquals(StringBuilder where, List<Object> parameters, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.append(" AND UPPER(").append(column).append(")=UPPER(?) ");
        parameters.add(value.strip());
    }

    private StudySummary mapStudy(ResultSet row, int index) throws SQLException {
        return new StudySummary(uuid(row.getBytes("id")), row.getString("institutional_code"), row.getString("title"),
                row.getString("academic_year"), nullableInteger(row, "completion_year"), row.getString("department_code"),
                row.getString("department_name"), row.getString("program_name"), row.getString("lifecycle_status"),
                row.getString("visibility"), row.getString("abstract_text"), row.getString("problem_statement"),
                row.getString("methodology"), splitKeywords(row.getString("keywords_text")), row.getString("results_text"),
                row.getInt("objective_count"));
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static List<String> splitKeywords(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(term -> !term.isBlank()).toList();
    }

    private static String orderBy(String sort) {
        return switch (sort) {
            case "YEAR_ASC" -> " ORDER BY s.completion_year ASC, s.title ASC, s.id ASC";
            case "TITLE_ASC" -> " ORDER BY s.title ASC, s.id ASC";
            case "TITLE_DESC" -> " ORDER BY s.title DESC, s.id ASC";
            default -> " ORDER BY s.completion_year DESC, s.title ASC, s.id ASC";
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("\\", "!\\")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    static PageWindow pageWindow(int requestedPage, int requestedSize) {
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
        int nonNegativePage = Math.max(0, requestedPage);
        int page = (int) Math.min((long) nonNegativePage, Integer.MAX_VALUE / (long) size);
        int offset = Math.toIntExact((long) page * size);
        return new PageWindow(page, size, offset);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record SearchFilter(String query, String department, Integer yearFrom, Integer yearTo,
                               String lifecycle, String topic, int page, int size, String sort) {}

    public record SearchPage(List<StudySummary> items, long totalItems, int page, int pageSize, Instant generatedAt) {}

    record PageWindow(int page, int size, int offset) {}

    public record StudySummary(UUID id, String institutionalCode, String title, String academicYear,
                               Integer completionYear, String departmentCode, String departmentName,
                               String program, String lifecycleStatus, String visibility, String abstractText,
                               String problemStatement, String methodology, List<String> keywords,
                               String resultsText, int objectiveCount) {}
}

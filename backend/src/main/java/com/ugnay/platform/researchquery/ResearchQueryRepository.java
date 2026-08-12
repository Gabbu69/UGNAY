package com.ugnay.platform.researchquery;

import com.ugnay.platform.identity.StudyVisibilityPolicy;
import com.ugnay.platform.researchquery.ResearchQueryAst.Comparator;
import com.ugnay.platform.researchquery.ResearchQueryAst.ContextKind;
import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.Field;
import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import com.ugnay.platform.researchquery.ResearchQueryAst.Predicate;
import com.ugnay.platform.researchquery.ResearchQueryAst.StringLiteral;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.SEMANTIC;

/**
 * Database boundary for the interpreter. Every statement is fixed and every external value is bound.
 * The language never supplies an identifier, fragment, sort clause, or SQL operator.
 */
@Repository
public class ResearchQueryRepository {
    static final int MAX_CANDIDATES = 10_000;
    private static final int SQL_TIMEOUT_SECONDS = 4;
    private static final String STUDY_COLUMNS = """
            s.id, s.institutional_code, s.title, s.academic_year, s.department_id,
            d.code AS department_code, d.name AS department_name,
            s.lifecycle_status, s.visibility, s.abstract_text, s.problem_statement,
            s.methodology, s.features_text, s.data_sources_text, s.technology_text,
            s.intended_users_text, s.stakeholders_text, s.site_context, s.keywords_text,
            NULL AS warehouse_completion_year, s.published_at, s.created_at
            """;
    private static final String WAREHOUSE_STUDY_COLUMNS = """
            s.study_id AS id, s.institutional_code, s.title, s.academic_year, s.department_id,
            d.department_code, d.department_name,
            s.lifecycle_status, s.visibility, s.abstract_text, s.problem_statement,
            s.methodology, s.features_text, s.data_sources_text, s.technology_text,
            s.intended_users_text, s.stakeholders_text, s.site_context, s.keywords_text,
            s.completion_year AS warehouse_completion_year, s.published_at, s.source_created_at AS created_at
            """;
    private static final String ALL_STUDIES_SQL = "SELECT " + STUDY_COLUMNS
            + " FROM studies s LEFT JOIN departments d ON d.id=s.department_id WHERE 1=1";
    private static final String STUDY_BY_ID_SQL = "SELECT " + STUDY_COLUMNS
            + " FROM studies s LEFT JOIN departments d ON d.id=s.department_id WHERE s.id=?";
    private static final String STUDY_BY_REFERENCE_SQL = "SELECT " + STUDY_COLUMNS
            + " FROM studies s LEFT JOIN departments d ON d.id=s.department_id"
            + " WHERE LOWER(s.institutional_code)=LOWER(?) OR LOWER(s.title)=LOWER(?) ORDER BY s.id";
    private static final String WAREHOUSE_STUDIES_SQL = "SELECT " + WAREHOUSE_STUDY_COLUMNS
            + " FROM dw_study_dimensions s LEFT JOIN dw_department_dimensions d"
            + " ON d.snapshot_id=s.snapshot_id AND d.department_id=s.department_id"
            + " WHERE s.snapshot_id=?";
    private static final String PROPOSAL_COLUMNS = """
            p.id, p.proposed_title, p.proposed_solution, p.methodology, p.technology_text,
            p.data_sources_text, p.intended_users_text, p.submitted_by,
            pc.problem_statement, pc.desired_outcome, pc.site_context, pc.department_id, pc.created_by,
            pr.id AS project_id
            """;
    private static final String PROPOSAL_BY_ID_SQL = "SELECT " + PROPOSAL_COLUMNS
            + " FROM proposals p JOIN problem_cases pc ON pc.id=p.problem_case_id"
            + " LEFT JOIN projects pr ON pr.proposal_id=p.id WHERE p.id=?";
    private static final String PROPOSAL_BY_TITLE_SQL = "SELECT " + PROPOSAL_COLUMNS
            + " FROM proposals p JOIN problem_cases pc ON pc.id=p.problem_case_id"
            + " LEFT JOIN projects pr ON pr.proposal_id=p.id WHERE LOWER(p.proposed_title)=LOWER(?) ORDER BY p.id";

    private final JdbcTemplate jdbc;
    private final StudyVisibilityPolicy studyVisibility;

    public ResearchQueryRepository(JdbcTemplate jdbc, StudyVisibilityPolicy studyVisibility) {
        this.jdbc = jdbc;
        this.studyVisibility = studyVisibility;
    }

    @Transactional(readOnly = true)
    public Preparation prepare(QueryPlan plan, Authentication authentication) {
        List<QueryDiagnostic> diagnostics = validateResearchAreas(plan.filter());
        if (!diagnostics.isEmpty()) return new Preparation(null, diagnostics);

        if (plan.context() == null) {
            return new Preparation(new PreparedPlan(plan, queryText(plan.filter()), null, null, null), List.of());
        }
        return switch (plan.context().kind()) {
            case TEXT -> new Preparation(new PreparedPlan(plan, normalizeSpace(plan.context().reference()),
                    null, null, "TEXT"), List.of());
            case THESIS -> prepareThesis(plan, authentication);
            case PROPOSAL -> prepareProposal(plan, authentication);
        };
    }

    @Transactional(readOnly = true)
    public CandidateLoad candidates(Authentication authentication) {
        Optional<WarehouseSnapshot> snapshot = latestWarehouseSnapshot();
        StudyVisibilityPolicy.Scope scope = studyVisibility.scope(authentication);
        if (!scope.authenticated()) {
            WarehouseSnapshot used = snapshot.orElse(null);
            return new CandidateLoad(List.of(), false, used == null ? null : used.id(),
                    used == null ? null : used.publishedAt());
        }
        StudyVisibilityPolicy.SqlRestriction restriction = studyVisibility.studyTableRestriction(scope);
        List<BaseStudy> base;
        if (snapshot.isPresent()) {
            String sql = WAREHOUSE_STUDIES_SQL + restriction.clause() + " ORDER BY s.study_id";
            base = query(sql, statement -> {
                statement.setBytes(1, bytes(snapshot.orElseThrow().id()));
                bind(statement, 2, restriction.parameters());
                statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_CANDIDATES + 1);
            }, this::baseStudy);
        } else {
            String sql = ALL_STUDIES_SQL + restriction.clause() + " ORDER BY s.id";
            base = query(sql, statement -> {
                bind(statement, 1, restriction.parameters());
                statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_CANDIDATES + 1);
            }, this::baseStudy);
        }
        boolean truncated = base.size() > MAX_CANDIDATES;
        if (truncated) base = new ArrayList<>(base.subList(0, MAX_CANDIDATES));
        WarehouseSnapshot used = snapshot.orElse(null);
        return new CandidateLoad(enrich(base, used == null ? null : used.id()), truncated,
                used == null ? null : used.id(), used == null ? null : used.publishedAt());
    }

    private Preparation prepareThesis(QueryPlan plan, Authentication authentication) {
        QueryPlan.ContextSpec spec = plan.context();
        List<BaseStudy> matches = parseUuid(spec.reference())
                .map(id -> query(STUDY_BY_ID_SQL, statement -> {
                    statement.setBytes(1, bytes(id));
                    statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                }, this::baseStudy))
                .orElseGet(() -> query(STUDY_BY_REFERENCE_SQL, statement -> {
                    statement.setString(1, spec.reference().strip());
                    statement.setString(2, spec.reference().strip());
                    statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                    statement.setMaxRows(2);
                }, this::baseStudy));
        if (matches.size() != 1 || !studyVisibility.canView(authentication, matches.getFirst().visibility(),
                matches.getFirst().departmentCode() == null
                        ? matches.getFirst().departmentName() : matches.getFirst().departmentCode())) {
            return unavailableContext(spec.span(), "SEM_THESIS_CONTEXT_UNAVAILABLE",
                    "The thesis context is unavailable, ambiguous, or not authorized.");
        }
        StudyRecord study = enrich(matches, null).getFirst();
        return new Preparation(new PreparedPlan(plan, study.profileText(), study.id(), null, "AUTHORIZED_THESIS"),
                List.of());
    }

    private Preparation prepareProposal(QueryPlan plan, Authentication authentication) {
        QueryPlan.ContextSpec spec = plan.context();
        UUID requestedId = spec.selectedProposalId() != null ? spec.selectedProposalId() : parseUuid(spec.reference()).orElse(null);
        List<ProposalRow> matches = requestedId != null
                ? query(PROPOSAL_BY_ID_SQL, statement -> {
                    statement.setBytes(1, bytes(requestedId));
                    statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                }, this::proposalRow)
                : query(PROPOSAL_BY_TITLE_SQL, statement -> {
                    statement.setString(1, spec.reference().strip());
                    statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                    statement.setMaxRows(2);
                }, this::proposalRow);
        if (matches.size() != 1 || !authorizedProposal(matches.isEmpty() ? null : matches.getFirst(), authentication)) {
            return unavailableContext(spec.span(), "SEM_PROPOSAL_CONTEXT_UNAVAILABLE",
                    "The proposal context is unavailable, ambiguous, or not authorized.");
        }
        ProposalRow proposal = matches.getFirst();
        List<String> objectives = jdbc.query(
                "SELECT statement_text FROM proposal_objectives WHERE proposal_id=? ORDER BY objective_order",
                (row, index) -> row.getString(1), proposal.id());
        String text = join(proposal.title(), proposal.problemStatement(), proposal.desiredOutcome(),
                String.join(" ", objectives), proposal.proposedSolution(), proposal.methodology(), proposal.technology(),
                proposal.dataSources(), proposal.intendedUsers(), proposal.siteContext());
        return new Preparation(new PreparedPlan(plan, text, null, uuid(proposal.id()), "AUTHORIZED_PROPOSAL"), List.of());
    }

    private boolean authorizedProposal(ProposalRow proposal, Authentication authentication) {
        if (proposal == null || authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) return false;
        if (isCurator(authentication)) return true;
        Optional<AccountRow> accountResult = account(authentication);
        if (accountResult.isEmpty()) return false;
        AccountRow account = accountResult.orElseThrow();
        if (account.departmentId() == null || !Arrays.equals(account.departmentId(), proposal.departmentId())) return false;
        if (proposal.projectId() == null) return true;
        Integer memberships = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_memberships WHERE project_id=? AND user_id=?",
                Integer.class, proposal.projectId(), account.id());
        return memberships != null && memberships > 0;
    }

    private List<QueryDiagnostic> validateResearchAreas(Expression expression) {
        List<Predicate> predicates = new ArrayList<>();
        collect(expression, Field.RESEARCH_AREA, predicates);
        List<QueryDiagnostic> diagnostics = new ArrayList<>();
        for (Predicate predicate : predicates) {
            if (!(predicate.value() instanceof StringLiteral value)) continue;
            boolean contains = predicate.comparator() == Comparator.CONTAINS;
            String sql = contains
                    ? "SELECT COUNT(*) FROM taxonomy_terms WHERE term_type='RESEARCH_AREA' AND active=TRUE"
                            + " AND (LOWER(canonical_label) LIKE LOWER(?) ESCAPE '!'"
                            + " OR LOWER(COALESCE(filipino_label,'')) LIKE LOWER(?) ESCAPE '!')"
                    : "SELECT COUNT(*) FROM taxonomy_terms WHERE term_type='RESEARCH_AREA' AND active=TRUE"
                            + " AND (LOWER(canonical_label)=LOWER(?) OR LOWER(COALESCE(filipino_label,''))=LOWER(?))";
            String parameter = contains ? "%" + escapeLike(value.value().strip()) + "%" : value.value().strip();
            Integer count = jdbc.queryForObject(sql, Integer.class, parameter, parameter);
            if (count == null || count == 0) {
                diagnostics.add(new QueryDiagnostic(SEMANTIC, "SEM_UNKNOWN_RESEARCH_AREA",
                        "RESEARCH_AREA must refer to an active curated taxonomy term.", value.span(), List.of()));
            }
        }
        return List.copyOf(diagnostics);
    }

    private List<StudyRecord> enrich(List<BaseStudy> base, UUID snapshotId) {
        if (base.isEmpty()) return List.of();
        Set<UUID> selected = new LinkedHashSet<>();
        base.forEach(row -> selected.add(row.id()));
        Map<UUID, List<String>> objectives = new LinkedHashMap<>();
        String objectiveSql = snapshotId == null
                ? "SELECT study_id,statement_text FROM study_objectives ORDER BY study_id,objective_order"
                : "SELECT study_id,statement_text FROM dw_study_objective_facts WHERE snapshot_id=?"
                        + " ORDER BY study_id,objective_order";
        queryRows(objectiveSql, statement -> {
            if (snapshotId != null) statement.setBytes(1, bytes(snapshotId));
            statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
        }, row -> {
                    UUID id = uuid(row.getBytes(1));
                    if (selected.contains(id)) objectives.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row.getString(2));
                });
        Map<UUID, List<TermRow>> terms = new LinkedHashMap<>();
        String termSql = snapshotId == null
                ? "SELECT st.study_id,t.term_type,t.canonical_label,t.filipino_label"
                        + " FROM study_terms st JOIN taxonomy_terms t ON t.id=st.term_id"
                        + " WHERE t.active=TRUE ORDER BY st.study_id,t.term_type,t.canonical_label"
                : "SELECT b.study_id,t.term_type,t.canonical_label,NULL AS filipino_label"
                        + " FROM dw_study_topic_bridge b JOIN dw_topic_dimensions t"
                        + " ON t.snapshot_id=b.snapshot_id AND t.term_id=b.term_id"
                        + " WHERE b.snapshot_id=? AND t.active=TRUE ORDER BY b.study_id,t.term_type,t.canonical_label";
        queryRows(termSql, statement -> {
            if (snapshotId != null) statement.setBytes(1, bytes(snapshotId));
            statement.setQueryTimeout(SQL_TIMEOUT_SECONDS);
        }, row -> {
                    UUID id = uuid(row.getBytes(1));
                    if (selected.contains(id)) terms.computeIfAbsent(id, ignored -> new ArrayList<>())
                            .add(new TermRow(row.getString(2), row.getString(3), row.getString(4)));
                });

        List<StudyRecord> result = new ArrayList<>(base.size());
        for (BaseStudy row : base) {
            LinkedHashSet<String> keywords = splitKeywords(row.keywordsText());
            LinkedHashSet<String> researchAreas = new LinkedHashSet<>();
            for (TermRow term : terms.getOrDefault(row.id(), List.of())) {
                LinkedHashSet<String> destination = "RESEARCH_AREA".equals(term.type()) ? researchAreas
                        : "KEYWORD".equals(term.type()) ? keywords : null;
                if (destination != null) {
                    addNonBlank(destination, term.canonical());
                    addNonBlank(destination, term.filipino());
                }
            }
            result.add(new StudyRecord(row.id(), row.code(), row.title(), row.academicYear(),
                    row.completionYear() == null ? strictYear(row.academicYear()) : row.completionYear(),
                    row.departmentCode(), row.departmentName(), row.lifecycle(), row.visibility(), row.abstractText(),
                    row.problemStatement(), List.copyOf(objectives.getOrDefault(row.id(), List.of())), List.copyOf(keywords),
                    List.copyOf(researchAreas), row.methodology(), row.features(), row.dataSources(), row.technology(),
                    row.intendedUsers(), row.stakeholders(), row.siteContext(), row.asOf()));
        }
        return List.copyOf(result);
    }

    private BaseStudy baseStudy(ResultSet row, int index) throws SQLException {
        Timestamp published = row.getTimestamp("published_at");
        Timestamp created = row.getTimestamp("created_at");
        int completionYearValue = row.getInt("warehouse_completion_year");
        Integer completionYear = row.wasNull() ? null : completionYearValue;
        return new BaseStudy(uuid(row.getBytes("id")), row.getString("institutional_code"), row.getString("title"),
                row.getString("academic_year"), row.getBytes("department_id"), row.getString("department_code"), row.getString("department_name"),
                row.getString("lifecycle_status"), row.getString("visibility"), row.getString("abstract_text"),
                row.getString("problem_statement"), row.getString("methodology"), row.getString("features_text"),
                row.getString("data_sources_text"), row.getString("technology_text"), row.getString("intended_users_text"),
                row.getString("stakeholders_text"), row.getString("site_context"), row.getString("keywords_text"), completionYear,
                published != null ? published.toInstant() : created == null ? null : created.toInstant());
    }

    private Optional<WarehouseSnapshot> latestWarehouseSnapshot() {
        return jdbc.query("SELECT id,published_at FROM warehouse_snapshots"
                        + " WHERE snapshot_status='PUBLISHED' ORDER BY published_at DESC,id DESC LIMIT 1",
                (row, index) -> new WarehouseSnapshot(uuid(row.getBytes(1)), row.getTimestamp(2).toInstant()))
                .stream().findFirst();
    }

    private Optional<AccountRow> account(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) return Optional.empty();
        return jdbc.query(
                "SELECT id,department_id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                (row, index) -> new AccountRow(row.getBytes(1), row.getBytes(2)), authentication.getName())
                .stream().findFirst();
    }

    private ProposalRow proposalRow(ResultSet row, int index) throws SQLException {
        return new ProposalRow(row.getBytes("id"), row.getString("proposed_title"), row.getString("problem_statement"),
                row.getString("desired_outcome"), row.getString("proposed_solution"), row.getString("methodology"),
                row.getString("technology_text"), row.getString("data_sources_text"), row.getString("intended_users_text"),
                row.getString("site_context"), row.getBytes("department_id"), row.getBytes("submitted_by"),
                row.getBytes("created_by"), row.getBytes("project_id"));
    }

    private <T> List<T> query(String sql, StatementConfigurer configurer, RowMapper<T> mapper) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            configurer.configure(statement);
            return statement;
        }, mapper);
    }

    private void queryRows(String sql, StatementConfigurer configurer, SqlRowConsumer consumer) {
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            configurer.configure(statement);
            return statement;
        }, (RowCallbackHandler) row -> consumer.accept(row));
    }

    private static void bind(PreparedStatement statement, int firstIndex, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof byte[] bytes) statement.setBytes(firstIndex + index, bytes);
            else statement.setObject(firstIndex + index, value);
        }
    }

    private static Preparation unavailableContext(SourceSpan span, String code, String message) {
        return new Preparation(null, List.of(new QueryDiagnostic(SEMANTIC, code, message, span, List.of())));
    }

    private static void collect(Expression expression, Field field, List<Predicate> destination) {
        if (expression == null) return;
        if (expression instanceof Predicate predicate) {
            if (predicate.field() == field) destination.add(predicate);
        } else if (expression instanceof Group group) collect(group.expression(), field, destination);
        else {
            Logical logical = (Logical) expression;
            collect(logical.left(), field, destination);
            collect(logical.right(), field, destination);
        }
    }

    private static String queryText(Expression expression) {
        List<String> values = new ArrayList<>();
        collectText(expression, values);
        return String.join(" ", values);
    }

    private static void collectText(Expression expression, List<String> values) {
        if (expression == null) return;
        if (expression instanceof Predicate predicate) {
            if (predicate.value() instanceof StringLiteral string
                    && predicate.field() != Field.DEPARTMENT && predicate.field() != Field.STATUS) {
                values.add(string.value());
            }
        } else if (expression instanceof Group group) collectText(group.expression(), values);
        else {
            Logical logical = (Logical) expression;
            collectText(logical.left(), values);
            collectText(logical.right(), values);
        }
    }

    private static LinkedHashSet<String> splitKeywords(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String part : value.split("[,;]")) addNonBlank(result, part);
        return result;
    }

    private static void addNonBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private static Integer strictYear(String value) {
        if (value == null || !value.strip().matches("[0-9]{4}")) return null;
        return Integer.valueOf(value.strip());
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static boolean restricted(String visibility) {
        return "RESTRICTED".equalsIgnoreCase(visibility) || "EMBARGOED".equalsIgnoreCase(visibility);
    }

    private static boolean isCurator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_CURATOR".equals(authority.getAuthority()));
    }

    private static Optional<UUID> parseUuid(String value) {
        try { return Optional.of(UUID.fromString(value == null ? "" : value.strip())); }
        catch (RuntimeException exception) { return Optional.empty(); }
    }

    private static String normalizeSpace(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(value.strip());
        }
        return result.toString();
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Preparation(PreparedPlan prepared, List<QueryDiagnostic> diagnostics) {
        public Preparation { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
        public boolean valid() { return prepared != null && diagnostics.isEmpty(); }
    }

    public record PreparedPlan(QueryPlan plan, String queryText, UUID sourceStudyId, UUID proposalId,
                               String contextLabel) {}

    public record CandidateLoad(List<StudyRecord> studies, boolean truncated, UUID warehouseSnapshotId,
                                Instant warehouseAsOf) {
        public CandidateLoad { studies = List.copyOf(studies); }
    }

    public record StudyRecord(
            UUID id, String code, String title, String academicYear, Integer year,
            String departmentCode, String departmentName, String lifecycle, String visibility,
            String abstractText, String problemStatement, List<String> objectives, List<String> keywords,
            List<String> researchAreas, String methodology, String features, String dataSources,
            String technology, String intendedUsers, String stakeholders, String siteContext, Instant asOf) {
        public StudyRecord {
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            researchAreas = researchAreas == null ? List.of() : List.copyOf(researchAreas);
        }

        public String profileText() {
            return join(code, title, abstractText, problemStatement, String.join(" ", objectives),
                    String.join(" ", keywords), String.join(" ", researchAreas), methodology, features,
                    dataSources, technology, intendedUsers, stakeholders, siteContext);
        }
    }

    private record BaseStudy(UUID id, String code, String title, String academicYear, byte[] departmentId, String departmentCode,
                             String departmentName, String lifecycle, String visibility, String abstractText,
                             String problemStatement, String methodology, String features, String dataSources,
                             String technology, String intendedUsers, String stakeholders, String siteContext,
                             String keywordsText, Integer completionYear, Instant asOf) {}
    private record WarehouseSnapshot(UUID id, Instant publishedAt) {}
    private record TermRow(String type, String canonical, String filipino) {}
    private record AccountRow(byte[] id, byte[] departmentId) {}
    private record ProposalRow(byte[] id, String title, String problemStatement, String desiredOutcome,
                               String proposedSolution, String methodology, String technology, String dataSources,
                               String intendedUsers, String siteContext, byte[] departmentId, byte[] submittedBy,
                               byte[] createdBy, byte[] projectId) {}

    @FunctionalInterface
    private interface StatementConfigurer { void configure(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface
    private interface SqlRowConsumer { void accept(ResultSet row) throws SQLException; }
}

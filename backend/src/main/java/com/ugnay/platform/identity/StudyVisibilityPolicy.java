package com.ugnay.platform.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One fail-closed policy for catalogue study reads.
 *
 * <p>Curators can read every study. Other authenticated accounts can read
 * PUBLIC/CAMPUS studies globally and any other non-restricted visibility only
 * inside their own department. RESTRICTED/EMBARGOED is curator-only.</p>
 */
@Service
public final class StudyVisibilityPolicy {
    private static final Set<String> GLOBAL = Set.of("PUBLIC", "CAMPUS");
    private static final Set<String> RESTRICTED = Set.of("RESTRICTED", "EMBARGOED");

    private final JdbcTemplate jdbc;

    public StudyVisibilityPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Scope scope(Authentication authentication) {
        if (!authenticated(authentication)) return Scope.anonymousScope();
        if (hasRole(authentication, "ROLE_CURATOR")) return Scope.curatorScope();
        List<Scope> accounts = jdbc.query(
                "SELECT d.id,d.code,d.name FROM user_accounts u LEFT JOIN departments d ON d.id=u.department_id "
                        + "WHERE LOWER(u.email)=LOWER(?) AND u.account_status='ACTIVE'",
                (row, index) -> new Scope(true, false,
                        row.getBytes(1) == null ? null : uuid(row.getBytes(1)),
                        row.getString(2), row.getString(3)), authentication.getName());
        return accounts.size() == 1 ? accounts.getFirst() : Scope.authenticatedWithoutDepartment();
    }

    public boolean canView(Authentication authentication, String visibility, String studyDepartment) {
        return canView(scope(authentication), visibility, studyDepartment);
    }

    public boolean canView(Scope scope, String visibility, String studyDepartment) {
        if (scope == null || !scope.authenticated()) return false;
        if (scope.curator()) return true;
        String normalizedVisibility = normalize(visibility);
        if (GLOBAL.contains(normalizedVisibility)) return true;
        if (normalizedVisibility.isEmpty() || protectedVisibility(normalizedVisibility)) return false;
        String normalizedDepartment = normalize(studyDepartment);
        return !normalizedDepartment.isEmpty()
                && (normalizedDepartment.equals(normalize(scope.departmentCode()))
                || normalizedDepartment.equals(normalize(scope.departmentName())));
    }

    /**
     * Central serialization guard for protected catalogue text. Restricted and
     * embargoed content must be redacted unless an authenticated curator is
     * producing the response, even when the row contributed to a derived score.
     */
    public boolean mustRedactProtectedText(Scope scope, String visibility) {
        return protectedVisibility(visibility)
                && (scope == null || !scope.authenticated() || !scope.curator());
    }

    public void requireVisible(Authentication authentication, String visibility, String studyDepartment) {
        if (!canView(authentication, visibility, studyDepartment)) {
            throw new AccessDeniedException("The requested study is not available to this account.");
        }
    }

    /**
     * Authorization predicate for queries whose study table alias is {@code s}.
     * Values remain bound parameters; no request data is placed in the SQL text.
     */
    public SqlRestriction studyTableRestriction(Scope scope) {
        if (scope != null && scope.authenticated() && scope.curator()) return SqlRestriction.none();
        if (scope == null || !scope.authenticated()) return new SqlRestriction(" AND 1=0 ", List.of());
        if (scope.departmentId() == null) {
            return new SqlRestriction(
                    " AND UPPER(COALESCE(s.visibility,'')) IN ('PUBLIC','CAMPUS') ", List.of());
        }
        return new SqlRestriction(
                " AND (UPPER(COALESCE(s.visibility,'')) IN ('PUBLIC','CAMPUS') "
                        + "OR (s.department_id=? AND UPPER(COALESCE(s.visibility,'')) "
                        + "NOT IN ('','RESTRICTED','EMBARGOED'))) ",
                List.of(bytes(scope.departmentId())));
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getName());
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(authority -> role.equals(authority.getAuthority()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean protectedVisibility(String visibility) {
        return RESTRICTED.contains(normalize(visibility));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Scope(boolean authenticated, boolean curator, UUID departmentId,
                        String departmentCode, String departmentName) {
        public static Scope anonymousScope() { return new Scope(false, false, null, null, null); }
        public static Scope authenticatedWithoutDepartment() { return new Scope(true, false, null, null, null); }
        public static Scope curatorScope() { return new Scope(true, true, null, null, null); }
    }

    public record SqlRestriction(String clause, List<Object> parameters) {
        public SqlRestriction {
            clause = clause == null ? "" : clause;
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        public static SqlRestriction none() { return new SqlRestriction("", List.of()); }
    }
}

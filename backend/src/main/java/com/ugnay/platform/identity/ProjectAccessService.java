package com.ugnay.platform.identity;

import com.ugnay.platform.shared.JdbcAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectAccessService {
    private static final Set<String> MEMBERSHIP_ROLES = Set.of("STUDENT", "ADVISER", "COORDINATOR", "REVIEWER");
    private final JdbcTemplate jdbc;
    private final JdbcAuditService audit;

    public ProjectAccessService(JdbcTemplate jdbc, JdbcAuditService audit,
            @Value("${ugnay.security.public-demo-read:true}") boolean ignoredPublicDemoRead) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeExplicitMemberships() {
        List<byte[]> unassigned = jdbc.query("SELECT p.id FROM projects p WHERE NOT EXISTS (SELECT 1 FROM project_memberships pm WHERE pm.project_id=p.id)",
                (row, index) -> row.getBytes(1));
        for (byte[] projectId : unassigned) {
            List<UserRoleRow> candidates = jdbc.query(
                    "SELECT DISTINCT u.id,r.code FROM projects p "
                            + "JOIN proposals q ON q.id=p.proposal_id "
                            + "LEFT JOIN proposal_decisions d ON d.proposal_id=q.id "
                            + "JOIN user_accounts u ON (u.id=q.submitted_by OR u.id=d.decided_by) "
                            + "JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id "
                            + "WHERE p.id=? AND u.account_status='ACTIVE' AND u.department_id=p.department_id "
                            + "AND r.code IN ('STUDENT','ADVISER','COORDINATOR','REVIEWER')",
                    (row, index) -> new UserRoleRow(row.getBytes(1), row.getString(2)), projectId);
            for (UserRoleRow candidate : candidates) {
                jdbc.update("INSERT INTO project_memberships(project_id,user_id,membership_role,joined_at) VALUES(?,?,?,?)",
                        projectId, candidate.userId(), candidate.role(), Timestamp.from(Instant.now()));
            }
        }
    }

    public boolean canAccess(Authentication authentication, UUID projectId) {
        if (!authenticated(authentication)) return false;
        if (hasRole(authentication, "ROLE_CURATOR")) return true;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM project_memberships pm JOIN user_accounts u ON u.id=pm.user_id JOIN projects p ON p.id=pm.project_id WHERE pm.project_id=? AND LOWER(u.email)=LOWER(?) AND u.account_status='ACTIVE' AND u.department_id=p.department_id",
                Integer.class, bytes(projectId), authentication.getName());
        return count != null && count > 0;
    }

    public void requireAccess(Authentication authentication, UUID projectId) {
        if (!canAccess(authentication, projectId)) {
            throw new org.springframework.security.access.AccessDeniedException("You are not a member of the selected project.");
        }
    }

    /**
     * Route-evidence reads are never covered by the public-demo shortcut. A
     * non-curator must belong to the proposal department and, once the proposal
     * has a project, hold an explicit membership in that project.
     */
    public boolean canAccessProposal(Authentication authentication, UUID proposalId) {
        if (!authenticated(authentication)) return false;
        if (hasRole(authentication, "ROLE_CURATOR")) return true;
        List<ProposalAccessRow> proposals = jdbc.query(
                "SELECT pc.department_id,pr.id,p.submitted_by FROM proposals p JOIN problem_cases pc ON pc.id=p.problem_case_id "
                        + "LEFT JOIN projects pr ON pr.proposal_id=p.id WHERE p.id=?",
                (row, index) -> new ProposalAccessRow(row.getBytes(1), row.getBytes(2), row.getBytes(3)), bytes(proposalId));
        if (proposals.size() != 1) return false;
        List<AccountAccessRow> accounts = jdbc.query(
                "SELECT id,department_id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                (row, index) -> new AccountAccessRow(row.getBytes(1), row.getBytes(2)), authentication.getName());
        if (accounts.size() != 1 || accounts.getFirst().departmentId() == null
                || !Arrays.equals(accounts.getFirst().departmentId(), proposals.getFirst().departmentId())) return false;
        if (proposals.getFirst().projectId() == null) {
            return Arrays.equals(accounts.getFirst().userId(), proposals.getFirst().submittedBy());
        }
        Integer memberships = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_memberships WHERE project_id=? AND user_id=?",
                Integer.class, proposals.getFirst().projectId(), accounts.getFirst().userId());
        return memberships != null && memberships > 0;
    }

    public void requireProposalAccess(Authentication authentication, UUID proposalId) {
        if (!canAccessProposal(authentication, proposalId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "The proposal route evidence is not available to this account.");
        }
    }

    public boolean canAccessProblem(Authentication authentication, UUID problemId) {
        if (!authenticated(authentication)) return false;
        if (hasRole(authentication, "ROLE_CURATOR")) return true;
        List<ProblemAccessRow> problems = jdbc.query(
                "SELECT pc.department_id,pc.created_by,q.submitted_by,p.id FROM problem_cases pc "
                        + "LEFT JOIN proposals q ON q.problem_case_id=pc.id LEFT JOIN projects p ON p.proposal_id=q.id "
                        + "WHERE pc.id=?",
                (row, index) -> new ProblemAccessRow(row.getBytes(1), row.getBytes(2), row.getBytes(3), row.getBytes(4)), bytes(problemId));
        if (problems.isEmpty()) return false;
        List<AccountAccessRow> accounts = jdbc.query(
                "SELECT id,department_id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                (row, index) -> new AccountAccessRow(row.getBytes(1), row.getBytes(2)), authentication.getName());
        if (accounts.size() != 1 || accounts.getFirst().departmentId() == null
                || !Arrays.equals(accounts.getFirst().departmentId(), problems.getFirst().departmentId())) return false;
        byte[] userId = accounts.getFirst().userId();
        return problems.stream().anyMatch(problem -> {
            if (problem.projectId() == null) {
                return Arrays.equals(userId, problem.createdBy())
                        || problem.submittedBy() != null && Arrays.equals(userId, problem.submittedBy());
            }
            Integer memberships = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project_memberships WHERE project_id=? AND user_id=?",
                    Integer.class, problem.projectId(), userId);
            return memberships != null && memberships > 0;
        });
    }

    public void requireProblemAccess(Authentication authentication, UUID problemId) {
        if (!canAccessProblem(authentication, problemId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "The problem intake is not available to this account.");
        }
    }

    public List<MembershipView> memberships(UUID projectId) {
        return jdbc.query("SELECT u.id, u.display_name, u.email, pm.membership_role, pm.joined_at FROM project_memberships pm JOIN user_accounts u ON u.id=pm.user_id WHERE pm.project_id=? ORDER BY u.display_name, pm.membership_role",
                (row, index) -> new MembershipView(uuid(row.getBytes(1)), row.getString(2), row.getString(3), row.getString(4),
                        row.getTimestamp(5).toInstant()), bytes(projectId));
    }

    @Transactional
    public MembershipView grant(UUID projectId, UUID userId, String roleValue, String actorEmail) {
        String role = roleValue == null ? "" : roleValue.strip().toUpperCase();
        if (!MEMBERSHIP_ROLES.contains(role)) throw new IllegalArgumentException("Unsupported project membership role: " + role + ".");
        Integer sameDepartment = jdbc.queryForObject("SELECT COUNT(*) FROM projects p JOIN user_accounts u ON u.id=? WHERE p.id=? AND p.department_id=u.department_id AND u.account_status='ACTIVE'",
                Integer.class, bytes(userId), bytes(projectId));
        if (sameDepartment == null || sameDepartment == 0) throw new IllegalArgumentException("Project members must be active accounts in the same department.");
        int existing = jdbc.queryForObject("SELECT COUNT(*) FROM project_memberships WHERE project_id=? AND user_id=? AND membership_role=?",
                Integer.class, bytes(projectId), bytes(userId), role);
        Instant now = Instant.now();
        if (existing == 0) jdbc.update("INSERT INTO project_memberships(project_id, user_id, membership_role, joined_at) VALUES(?,?,?,?)",
                bytes(projectId), bytes(userId), role, Timestamp.from(now));
        audit.append(actorEmail, "PROJECT_MEMBERSHIP_GRANTED", "PROJECT", projectId,
                "Granted an explicitly scoped project membership.", java.util.Map.of("userId", userId.toString(), "role", role));
        return jdbc.query("SELECT u.id, u.display_name, u.email, pm.membership_role, pm.joined_at FROM project_memberships pm JOIN user_accounts u ON u.id=pm.user_id WHERE pm.project_id=? AND pm.user_id=? AND pm.membership_role=?",
                (row, index) -> new MembershipView(uuid(row.getBytes(1)), row.getString(2), row.getString(3), row.getString(4), row.getTimestamp(5).toInstant()),
                bytes(projectId), bytes(userId), role).getFirst();
    }

    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getName());
    }
    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> role.equals(authority.getAuthority()));
    }
    public record MembershipView(UUID userId, String displayName, String email, String role, Instant joinedAt) {}
    private record UserRoleRow(byte[] userId, String role) {}
    private record ProposalAccessRow(byte[] departmentId, byte[] projectId, byte[] submittedBy) {}
    private record ProblemAccessRow(byte[] departmentId, byte[] createdBy, byte[] submittedBy, byte[] projectId) {}
    private record AccountAccessRow(byte[] userId, byte[] departmentId) {}
}

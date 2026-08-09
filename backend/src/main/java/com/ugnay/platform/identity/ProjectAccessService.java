package com.ugnay.platform.identity;

import com.ugnay.platform.shared.JdbcAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectAccessService {
    private static final Set<String> MEMBERSHIP_ROLES = Set.of("STUDENT", "ADVISER", "COORDINATOR", "REVIEWER");
    private final JdbcTemplate jdbc;
    private final JdbcAuditService audit;
    private final boolean publicDemoRead;

    public ProjectAccessService(JdbcTemplate jdbc, JdbcAuditService audit,
            @Value("${ugnay.security.public-demo-read:true}") boolean publicDemoRead) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.publicDemoRead = publicDemoRead;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeExplicitMemberships() {
        List<byte[]> unassigned = jdbc.query("SELECT p.id FROM projects p WHERE NOT EXISTS (SELECT 1 FROM project_memberships pm WHERE pm.project_id=p.id)",
                (row, index) -> row.getBytes(1));
        for (byte[] projectId : unassigned) {
            List<UserRoleRow> candidates = jdbc.query("SELECT DISTINCT u.id,r.role_code FROM user_accounts u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id JOIN projects p ON p.id=? WHERE u.account_status='ACTIVE' AND u.department_id=p.department_id AND r.role_code IN ('STUDENT','ADVISER','COORDINATOR','REVIEWER')",
                    (row, index) -> new UserRoleRow(row.getBytes(1), row.getString(2)), projectId);
            for (UserRoleRow candidate : candidates) {
                jdbc.update("INSERT INTO project_memberships(project_id,user_id,membership_role,joined_at) VALUES(?,?,?,?)",
                        projectId, candidate.userId(), candidate.role(), Timestamp.from(Instant.now()));
            }
        }
    }

    public boolean canAccess(Authentication authentication, UUID projectId) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) return true;
        if (authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_CURATOR"))) return true;
        if (publicDemoRead) return true;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM project_memberships pm JOIN user_accounts u ON u.id=pm.user_id JOIN projects p ON p.id=pm.project_id WHERE pm.project_id=? AND LOWER(u.email)=LOWER(?) AND u.account_status='ACTIVE' AND u.department_id=p.department_id",
                Integer.class, bytes(projectId), authentication.getName());
        return count != null && count > 0;
    }

    public void requireAccess(Authentication authentication, UUID projectId) {
        if (!canAccess(authentication, projectId)) {
            throw new org.springframework.security.access.AccessDeniedException("You are not a member of the selected project.");
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
    public record MembershipView(UUID userId, String displayName, String email, String role, Instant joinedAt) {}
    private record UserRoleRow(byte[] userId, String role) {}
}

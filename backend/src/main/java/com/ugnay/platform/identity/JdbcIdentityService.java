package com.ugnay.platform.identity;

import com.ugnay.platform.shared.JdbcAuditService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class JdbcIdentityService implements UserDetailsService, ApplicationRunner {
    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "ADVISER", "COORDINATOR", "CURATOR");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final SecureRandom random = new SecureRandom();
    private final JdbcAuditService audit;

    public JdbcIdentityService(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${ugnay.security.bootstrap-admin-email}") String bootstrapEmail,
            @Value("${ugnay.security.bootstrap-admin-password}") String bootstrapPassword,
            JdbcAuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.bootstrapEmail = bootstrapEmail.toLowerCase(Locale.ROOT);
        this.bootstrapPassword = bootstrapPassword;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID departmentId = stableId("department-cics");
        if (count("SELECT COUNT(*) FROM departments WHERE code = ?", "CICS") == 0) {
            jdbc.update("INSERT INTO departments(id, code, name, active, row_version, created_at) VALUES(?,?,?,?,?,?)",
                    bytes(departmentId), "CICS", "College of Information and Computing Sciences", true, 0, Timestamp.from(Instant.now()));
        }
        for (String role : ALLOWED_ROLES) {
            if (count("SELECT COUNT(*) FROM roles WHERE code = ?", role) == 0) {
                jdbc.update("INSERT INTO roles(id, code, label) VALUES(?,?,?)", bytes(stableId("role-" + role)), role,
                        title(role));
            }
        }
        if (count("SELECT COUNT(*) FROM user_accounts WHERE email = ?", bootstrapEmail) == 0) {
            UUID userId = stableId("bootstrap-" + bootstrapEmail);
            Instant now = Instant.now();
            jdbc.update("INSERT INTO user_accounts(id, department_id, email, display_name, account_status, row_version, created_at) VALUES(?,?,?,?,?,?,?)",
                    bytes(userId), bytes(departmentId), bootstrapEmail, "UGNAY Pilot Administrator", "ACTIVE", 0, Timestamp.from(now));
            jdbc.update("INSERT INTO password_credentials(user_id, password_hash, password_changed_at) VALUES(?,?,?)",
                    bytes(userId), encoder.encode(bootstrapPassword), Timestamp.from(now));
            for (String role : ALLOWED_ROLES) assignRole(userId, role, now);
            audit.append(bootstrapEmail, "BOOTSTRAP_ADMIN_CREATED", "USER", userId,
                    "Created the invite-only pilot bootstrap administrator.", Map.of("roles", ALLOWED_ROLES));
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username.toLowerCase(Locale.ROOT);
        List<AccountRow> accounts = jdbc.query("SELECT u.id, u.email, u.account_status, p.password_hash FROM user_accounts u JOIN password_credentials p ON p.user_id = u.id WHERE u.email = ?",
                (result, row) -> new AccountRow(result.getBytes("id"), result.getString("email"),
                        result.getString("account_status"), result.getString("password_hash")), email);
        if (accounts.isEmpty()) throw new UsernameNotFoundException("No active invited account exists for " + email + ".");
        AccountRow account = accounts.getFirst();
        List<String> roles = jdbc.query("SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id WHERE ur.user_id = ? ORDER BY r.code",
                (result, row) -> result.getString(1), account.id());
        return User.withUsername(account.email()).password(account.passwordHash()).roles(roles.toArray(String[]::new))
                .disabled(!"ACTIVE".equals(account.status())).build();
    }

    public List<UserView> users() {
        return jdbc.query("SELECT u.id, u.email, u.display_name, u.account_status, d.name AS department FROM user_accounts u LEFT JOIN departments d ON d.id = u.department_id ORDER BY u.display_name",
                (result, row) -> new UserView(uuid(result.getBytes("id")), result.getString("email"),
                        result.getString("display_name"), result.getString("department"), result.getString("account_status"),
                        roles(result.getBytes("id"))));
    }

    public Optional<UserView> userByEmail(String emailValue) {
        if (emailValue == null || emailValue.isBlank()) return Optional.empty();
        String email = emailValue.toLowerCase(Locale.ROOT).trim();
        return jdbc.query("SELECT u.id, u.email, u.display_name, u.account_status, d.name AS department FROM user_accounts u LEFT JOIN departments d ON d.id = u.department_id WHERE u.email = ?",
                        (result, row) -> new UserView(uuid(result.getBytes("id")), result.getString("email"),
                                result.getString("display_name"), result.getString("department"), result.getString("account_status"),
                                roles(result.getBytes("id"))), email)
                .stream().findFirst();
    }

    public List<InvitationView> invitations() {
        return jdbc.query("SELECT id, email, intended_role, expires_at, accepted_at, created_at FROM invitations ORDER BY created_at DESC",
                (result, row) -> new InvitationView(uuid(result.getBytes("id")), result.getString("email"),
                        result.getString("intended_role"), result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("accepted_at") == null ? null : result.getTimestamp("accepted_at").toInstant(),
                        result.getTimestamp("created_at").toInstant(), null));
    }

    @Transactional
    public InvitationView invite(String emailValue, String roleValue, String invitedByEmail) {
        String email = emailValue.toLowerCase(Locale.ROOT).trim();
        String role = roleValue.toUpperCase(Locale.ROOT).trim();
        if (!ALLOWED_ROLES.contains(role)) throw new IllegalArgumentException("Unsupported invitation role: " + role + ".");
        if (count("SELECT COUNT(*) FROM user_accounts WHERE email = ?", email) > 0) throw new IllegalArgumentException("An account already exists for " + email + ".");
        if (count("SELECT COUNT(*) FROM invitations WHERE email = ? AND accepted_at IS NULL AND expires_at > ?",
                email, Timestamp.from(Instant.now())) > 0) {
            throw new IllegalArgumentException("An active invitation already exists for " + email + ".");
        }
        byte[] inviter = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", byte[].class, invitedByEmail.toLowerCase(Locale.ROOT));
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();
        Instant expires = now.plus(72, ChronoUnit.HOURS);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invitations(id, email, token_hash, intended_role, invited_by, expires_at, accepted_at, created_at) VALUES(?,?,?,?,?,?,?,?)",
                bytes(id), email, sha256(rawToken), role, inviter, Timestamp.from(expires), null, Timestamp.from(now));
        audit.append(invitedByEmail, "INVITATION_CREATED", "INVITATION", id,
                "Created a single-use invitation for " + email + ".", Map.of("role", role, "expiresAt", expires.toString()));
        return new InvitationView(id, email, role, expires, null, now, rawToken);
    }

    @Transactional
    public UserView accept(String rawToken, String displayName, String password) {
        String tokenHash = sha256(rawToken);
        List<InviteRow> matches = jdbc.query("SELECT id, email, intended_role, invited_by, expires_at, accepted_at FROM invitations WHERE token_hash = ?",
                (result, row) -> new InviteRow(result.getBytes("id"), result.getString("email"), result.getString("intended_role"),
                        result.getBytes("invited_by"), result.getTimestamp("expires_at").toInstant(), result.getTimestamp("accepted_at")), tokenHash);
        if (matches.isEmpty()) throw new IllegalArgumentException("Invitation token is invalid.");
        InviteRow invite = matches.getFirst();
        if (invite.acceptedAt() != null) throw new IllegalArgumentException("Invitation token was already used.");
        if (!invite.expiresAt().isAfter(Instant.now())) throw new IllegalArgumentException("Invitation token has expired.");
        if (password.length() < 12 || password.length() > 128) throw new IllegalArgumentException("Password must contain 12 to 128 characters.");
        if (displayName == null || displayName.isBlank() || displayName.length() > 160) throw new IllegalArgumentException("Display name must contain 1 to 160 characters.");
        byte[] department = jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", byte[].class, invite.invitedBy());
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO user_accounts(id, department_id, email, display_name, account_status, row_version, created_at) VALUES(?,?,?,?,?,?,?)",
                bytes(userId), department, invite.email(), displayName, "ACTIVE", 0, Timestamp.from(now));
        jdbc.update("INSERT INTO password_credentials(user_id, password_hash, password_changed_at) VALUES(?,?,?)",
                bytes(userId), encoder.encode(password), Timestamp.from(now));
        assignRole(userId, invite.role(), now);
        // A user may have historical, expired invitations. Invalidate every unused sibling token
        // atomically once one token creates the account so no later acceptance reaches the unique email constraint.
        jdbc.update("UPDATE invitations SET accepted_at = ? WHERE email = ? AND accepted_at IS NULL", Timestamp.from(now), invite.email());
        audit.append(invite.email(), "INVITATION_ACCEPTED", "USER", userId,
                "Accepted an invite-only UGNAY account.", Map.of("role", invite.role()));
        return new UserView(userId, invite.email(), displayName, departmentName(department), "ACTIVE", List.of(invite.role()));
    }

    private void assignRole(UUID userId, String role, Instant assignedAt) {
        byte[] roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", byte[].class, role);
        jdbc.update("INSERT INTO user_roles(user_id, role_id, assigned_at) VALUES(?,?,?)", bytes(userId), roleId, Timestamp.from(assignedAt));
    }

    private List<String> roles(byte[] userId) {
        return jdbc.query("SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id WHERE ur.user_id = ? ORDER BY r.code",
                (result, row) -> result.getString(1), userId);
    }

    private String departmentName(byte[] departmentId) {
        return jdbc.queryForObject("SELECT name FROM departments WHERE id = ?", String.class, departmentId);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static UUID stableId(String value) { return UUID.nameUUIDFromBytes(("ugnay:" + value).getBytes(StandardCharsets.UTF_8)); }
    private static String title(String role) { return role.charAt(0) + role.substring(1).toLowerCase(Locale.ROOT); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { ByteBuffer value = ByteBuffer.wrap(bytes); return new UUID(value.getLong(), value.getLong()); }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record AccountRow(byte[] id, String email, String status, String passwordHash) {}
    private record InviteRow(byte[] id, String email, String role, byte[] invitedBy, Instant expiresAt, Timestamp acceptedAt) {}
    public record UserView(UUID id, String email, String displayName, String department, String status, List<String> roles) {}
    public record InvitationView(UUID id, String email, String intendedRole, Instant expiresAt, Instant acceptedAt,
                                 Instant createdAt, String oneTimeToken) {}
}

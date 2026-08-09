package com.ugnay.platform.shared;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JdbcAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Insert-only by design; there is no update or delete audit API. */
    public void append(String actorEmail, String action, String subjectType, UUID subjectId,
                       String summary, Map<String, ?> snapshot) {
        byte[] actorId = null;
        if (actorEmail != null) {
            List<byte[]> actors = jdbc.query("SELECT id FROM user_accounts WHERE email = ?",
                    (result, row) -> result.getBytes(1), actorEmail.toLowerCase());
            if (!actors.isEmpty()) actorId = actors.getFirst();
        }
        jdbc.update("INSERT INTO audit_events(id, actor_id, action_code, subject_type, subject_id, event_summary, event_snapshot_json, occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), actorId, action, subjectType, subjectId == null ? null : bytes(subjectId),
                summary, json(snapshot), Timestamp.from(Instant.now()));
    }

    public List<AuditView> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT a.id, a.action_code, a.subject_type, a.subject_id, a.event_summary, a.event_snapshot_json, a.occurred_at, u.email AS actor_email "
                        + "FROM audit_events a LEFT JOIN user_accounts u ON u.id = a.actor_id ORDER BY a.occurred_at DESC LIMIT " + safeLimit,
                (result, row) -> new AuditView(uuid(result.getBytes("id")), result.getString("action_code"),
                        result.getString("subject_type"), result.getBytes("subject_id") == null ? null : uuid(result.getBytes("subject_id")),
                        result.getString("event_summary"), result.getString("event_snapshot_json"), result.getString("actor_email"),
                        result.getTimestamp("occurred_at").toInstant()));
    }

    private String json(Map<String, ?> snapshot) {
        try { return objectMapper.writeValueAsString(snapshot == null ? Map.of() : snapshot); }
        catch (JacksonException exception) { throw new IllegalArgumentException("Audit snapshot could not be serialized.", exception); }
    }

    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { ByteBuffer value = ByteBuffer.wrap(bytes); return new UUID(value.getLong(), value.getLong()); }

    public record AuditView(UUID id, String action, String subjectType, UUID subjectId, String summary,
                            String snapshotJson, String actorEmail, Instant occurredAt) {}
}

package com.ugnay.platform.warehouse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable transaction boundary for automatic warehouse refresh requests. */
@Repository
class WarehouseRefreshQueueRepository {
    private final JdbcTemplate jdbc;

    WarehouseRefreshQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    UUID enqueue(String actorEmail, WarehouseRefreshRequested.Trigger trigger) {
        byte[] actor = jdbc.query(
                        "SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                        (row, index) -> row.getBytes(1), actorEmail)
                .stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("The warehouse refresh actor is not active."));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouse_refresh_requests(id,requested_by,trigger_code,request_status,attempt_count,warehouse_load_id,requested_at,started_at,completed_at,failure_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                bytes(id), actor, trigger.name(), "QUEUED", 0, null, Timestamp.from(Instant.now()), null, null, null);
        return id;
    }

    @Transactional
    Optional<ClaimedRefresh> claimNext() {
        while (true) {
            Optional<ClaimedRefresh> candidate = jdbc.query(
                            "SELECT q.id,u.email,q.trigger_code FROM warehouse_refresh_requests q"
                                    + " JOIN user_accounts u ON u.id=q.requested_by"
                                    + " WHERE q.request_status='QUEUED' ORDER BY q.requested_at,q.id LIMIT 1",
                            (row, index) -> new ClaimedRefresh(uuid(row.getBytes(1)), row.getString(2),
                                    WarehouseRefreshRequested.Trigger.valueOf(row.getString(3))))
                    .stream().findFirst();
            if (candidate.isEmpty()) return Optional.empty();
            int claimed = jdbc.update("UPDATE warehouse_refresh_requests SET request_status='RUNNING',"
                            + "attempt_count=attempt_count+1,started_at=?,completed_at=NULL,failure_reason=NULL"
                            + " WHERE id=? AND request_status='QUEUED'",
                    Timestamp.from(Instant.now()), bytes(candidate.orElseThrow().id()));
            if (claimed == 1) return candidate;
        }
    }

    @Transactional
    void complete(UUID requestId, UUID loadId) {
        jdbc.update("UPDATE warehouse_refresh_requests SET request_status='COMPLETED',warehouse_load_id=?,"
                        + "completed_at=?,failure_reason=NULL WHERE id=? AND request_status='RUNNING'",
                bytes(loadId), Timestamp.from(Instant.now()), bytes(requestId));
    }

    @Transactional
    void fail(UUID requestId, UUID loadId, String reason) {
        String safe = reason == null || reason.isBlank() ? "Warehouse refresh failed safely." : reason;
        if (safe.length() > 1000) safe = safe.substring(0, 1000);
        jdbc.update("UPDATE warehouse_refresh_requests SET request_status='FAILED',warehouse_load_id=?,"
                        + "completed_at=?,failure_reason=? WHERE id=? AND request_status='RUNNING'",
                loadId == null ? null : bytes(loadId), Timestamp.from(Instant.now()), safe, bytes(requestId));
    }

    @Transactional
    void recoverInterrupted() {
        Instant now = Instant.now();
        jdbc.update("UPDATE warehouse_load_stages SET stage_status='FAILED',details_json=?,completed_at=?"
                        + " WHERE stage_status='RUNNING' AND load_id IN"
                        + " (SELECT id FROM warehouse_loads WHERE load_status='RUNNING')",
                "{\"result\":\"INTERRUPTED_RESTART\"}", Timestamp.from(now));
        jdbc.update("UPDATE warehouse_snapshots SET snapshot_status='FAILED' WHERE snapshot_status='BUILDING'");
        jdbc.update("UPDATE warehouse_loads SET load_status='FAILED',failure_reason=?,completed_at=?"
                        + " WHERE load_status='RUNNING'",
                "Warehouse refresh was interrupted by an application restart; the latest published snapshot was retained.",
                Timestamp.from(now));
        jdbc.update("UPDATE warehouse_refresh_requests SET request_status='QUEUED',started_at=NULL,"
                + "completed_at=NULL,failure_reason=NULL WHERE request_status='RUNNING'");
    }

    boolean hasQueued() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse_refresh_requests WHERE request_status='QUEUED'", Integer.class);
        return count != null && count > 0;
    }

    record ClaimedRefresh(UUID id, String actorEmail, WarehouseRefreshRequested.Trigger trigger) {}

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

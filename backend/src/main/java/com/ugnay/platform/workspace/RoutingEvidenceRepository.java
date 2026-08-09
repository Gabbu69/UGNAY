package com.ugnay.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RoutingEvidenceRepository {
    private final JdbcTemplate jdbc;

    public RoutingEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ContinuationAssessment saveContinuation(UUID proposalId, UUID predecessorId,
            List<ObjectiveLink> links, boolean codeAccess, boolean dataAccess, String accessNotes, String actorEmail) {
        byte[] actor = actorId(actorEmail);
        byte[] proposal = bytes(proposalId);
        byte[] predecessor = bytes(predecessorId);
        Integer validPredecessor = jdbc.queryForObject("SELECT COUNT(*) FROM studies WHERE id=? AND lifecycle_status IN ('INCOMPLETE','SUSPENDED')",
                Integer.class, predecessor);
        if (validPredecessor == null || validPredecessor == 0) {
            throw new IllegalArgumentException("Continuation evidence requires an incomplete or suspended predecessor.");
        }
        jdbc.update("DELETE FROM proposal_objective_continuation_links WHERE proposal_id=?", proposal);
        for (ObjectiveLink link : links == null ? List.<ObjectiveLink>of() : links) {
            Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM proposal_objectives o JOIN continuation_items c ON c.id=? AND c.study_id=? AND c.item_status='OPEN' WHERE o.id=? AND o.proposal_id=?",
                    Integer.class, bytes(link.continuationItemId()), predecessor, bytes(link.proposalObjectiveId()), proposal);
            if (valid == null || valid == 0) throw new IllegalArgumentException("Every objective mapping must join this proposal to an open predecessor item.");
            jdbc.update("INSERT INTO proposal_objective_continuation_links(proposal_id, proposal_objective_id, continuation_item_id, rationale) VALUES(?,?,?,?)",
                    proposal, bytes(link.proposalObjectiveId()), bytes(link.continuationItemId()), required(link.rationale(), "Objective mapping rationale"));
        }
        if (exists("proposal_continuation_evidence", proposal)) {
            jdbc.update("UPDATE proposal_continuation_evidence SET predecessor_study_id=?, code_access_confirmed=?, data_access_confirmed=?, access_notes=?, recorded_by=?, row_version=row_version+1, recorded_at=? WHERE proposal_id=?",
                    predecessor, codeAccess, dataAccess, required(accessNotes, "Access notes"), actor, Timestamp.from(Instant.now()), proposal);
        } else {
            jdbc.update("INSERT INTO proposal_continuation_evidence(proposal_id, predecessor_study_id, code_access_confirmed, data_access_confirmed, access_notes, recorded_by, row_version, recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                    proposal, predecessor, codeAccess, dataAccess, required(accessNotes, "Access notes"), actor, 0, Timestamp.from(Instant.now()));
        }
        return continuationAssessment(proposalId, predecessorId);
    }

    @Transactional
    public ImprovementClaim saveImprovement(UUID proposalId, UUID predecessorId, UUID continuationItemId,
            String claim, String baseline, String target, String evaluationMethod, String actorEmail) {
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM continuation_items c JOIN studies s ON s.id=c.study_id WHERE c.id=? AND c.study_id=? AND c.item_type IN ('LIMITATION','RECOMMENDATION') AND s.lifecycle_status='COMPLETED'",
                Integer.class, bytes(continuationItemId), bytes(predecessorId));
        if (valid == null || valid == 0) throw new IllegalArgumentException("Improvement claims require a completed predecessor limitation or recommendation.");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO proposal_improvement_claims(id, proposal_id, predecessor_study_id, continuation_item_id, claim_text, baseline_measure, target_measure, evaluation_method, recorded_by, recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(proposalId), bytes(predecessorId), bytes(continuationItemId), required(claim, "Improvement claim"),
                required(baseline, "Baseline measure"), required(target, "Target measure"), required(evaluationMethod, "Evaluation method"),
                actorId(actorEmail), Timestamp.from(now));
        return new ImprovementClaim(id, proposalId, predecessorId, continuationItemId, claim.strip(), baseline.strip(),
                target.strip(), evaluationMethod.strip(), actorEmail, now);
    }

    public RouteAssessment assessment(UUID proposalId, UUID predecessorId) {
        ContinuationAssessment continuation = continuationAssessment(proposalId, predecessorId);
        int improvements = jdbc.queryForObject("SELECT COUNT(*) FROM proposal_improvement_claims WHERE proposal_id=? AND predecessor_study_id=?",
                Integer.class, bytes(proposalId), bytes(predecessorId));
        return new RouteAssessment(continuation.ready(), continuation.objectiveCoverage(), continuation.codeAccessConfirmed(),
                continuation.dataAccessConfirmed(), improvements > 0, improvements);
    }

    public ContinuationAssessment continuationAssessment(UUID proposalId, UUID predecessorId) {
        int objectives = jdbc.queryForObject("SELECT COUNT(*) FROM proposal_objectives WHERE proposal_id=?", Integer.class, bytes(proposalId));
        int mapped = jdbc.queryForObject("SELECT COUNT(DISTINCT proposal_objective_id) FROM proposal_objective_continuation_links WHERE proposal_id=?",
                Integer.class, bytes(proposalId));
        List<ContinuationRow> rows = jdbc.query("SELECT code_access_confirmed, data_access_confirmed, access_notes, row_version, recorded_at FROM proposal_continuation_evidence WHERE proposal_id=? AND predecessor_study_id=?",
                (result, index) -> new ContinuationRow(result.getBoolean(1), result.getBoolean(2), result.getString(3), result.getLong(4), result.getTimestamp(5).toInstant()),
                bytes(proposalId), bytes(predecessorId));
        double coverage = objectives == 0 ? 0 : mapped * 100.0 / objectives;
        if (rows.isEmpty()) return new ContinuationAssessment(proposalId, predecessorId, coverage, false, false, "", 0, null, false);
        ContinuationRow row = rows.getFirst();
        return new ContinuationAssessment(proposalId, predecessorId, coverage, row.code(), row.data(), row.notes(), row.version(), row.at(),
                coverage >= 60 && row.code() && row.data());
    }

    private byte[] actorId(String email) {
        if (email == null || email.isBlank()) return jdbc.queryForObject("SELECT id FROM user_accounts ORDER BY created_at LIMIT 1", byte[].class);
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?)", byte[].class, email);
    }

    private boolean exists(String table, byte[] id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE proposal_id=?", Integer.class, id) > 0;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }
    static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }

    public record ObjectiveLink(UUID proposalObjectiveId, UUID continuationItemId, String rationale) {}
    public record ContinuationAssessment(UUID proposalId, UUID predecessorStudyId, double objectiveCoverage,
            boolean codeAccessConfirmed, boolean dataAccessConfirmed, String accessNotes, long rowVersion,
            Instant recordedAt, boolean ready) {}
    public record ImprovementClaim(UUID id, UUID proposalId, UUID predecessorStudyId, UUID continuationItemId,
            String claim, String baseline, String target, String evaluationMethod, String recordedBy, Instant recordedAt) {}
    public record RouteAssessment(boolean continuationReady, double continuationCoverage, boolean codeAccess,
            boolean dataAccess, boolean improvementReady, int improvementClaimCount) {}
    private record ContinuationRow(boolean code, boolean data, String notes, long version, Instant at) {}
}

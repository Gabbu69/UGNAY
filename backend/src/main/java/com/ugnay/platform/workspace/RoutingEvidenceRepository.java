package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
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
    public synchronized ContinuationAssessment saveContinuation(UUID proposalId, UUID predecessorId,
            List<ObjectiveLink> links, boolean codeAccess, boolean dataAccess, String accessNotes, String actorEmail) {
        byte[] actor = actorId(actorEmail);
        byte[] proposal = bytes(proposalId);
        byte[] predecessor = bytes(predecessorId);
        Integer validPredecessor = jdbc.queryForObject("SELECT COUNT(*) FROM studies WHERE id=? AND lifecycle_status IN ('INCOMPLETE','SUSPENDED')",
                Integer.class, predecessor);
        if (validPredecessor == null || validPredecessor == 0) {
            throw new IllegalArgumentException("Continuation evidence requires an incomplete or suspended predecessor.");
        }
        List<ObjectiveLink> submittedLinks = links == null ? List.of() : List.copyOf(links);
        for (ObjectiveLink link : submittedLinks) {
            Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM proposal_objectives o JOIN continuation_items c ON c.id=? AND c.study_id=? AND c.item_status='OPEN' WHERE o.id=? AND o.proposal_id=?",
                    Integer.class, bytes(link.continuationItemId()), predecessor, bytes(link.proposalObjectiveId()), proposal);
            if (valid == null || valid == 0) throw new IllegalArgumentException("Every objective mapping must join this proposal to an open predecessor item.");
        }

        // Lock the proposal row so two submissions cannot allocate the same
        // proposal-scoped revision number.
        jdbc.queryForObject("SELECT id FROM proposals WHERE id=? FOR UPDATE", byte[].class, proposal);
        Long latestRevision = jdbc.queryForObject("SELECT MAX(revision_number) FROM proposal_continuation_revisions WHERE proposal_id=?",
                Long.class, proposal);
        long revisionNumber = latestRevision == null ? 1 : latestRevision + 1;
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO proposal_continuation_revisions(id, proposal_id, predecessor_study_id, revision_number, code_access_confirmed, data_access_confirmed, access_notes, recorded_by, recorded_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(revisionId), proposal, predecessor, revisionNumber, codeAccess, dataAccess,
                required(accessNotes, "Access notes"), actor, Timestamp.from(now));
        for (ObjectiveLink link : submittedLinks) {
            jdbc.update("INSERT INTO proposal_continuation_revision_links(revision_id, proposal_objective_id, continuation_item_id, rationale) VALUES(?,?,?,?)",
                    bytes(revisionId), bytes(link.proposalObjectiveId()), bytes(link.continuationItemId()),
                    required(link.rationale(), "Objective mapping rationale"));
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
        AssessmentStatus improvementState = improvements > 0 ? AssessmentStatus.ASSESSED : AssessmentStatus.UNASSESSED;
        return new RouteAssessment(continuation.state(), continuation.objectiveCoverage(), continuation.codeAccessConfirmed(),
                continuation.dataAccessConfirmed(), improvementState, improvements > 0 ? improvements : null);
    }

    public ContinuationAssessment continuationAssessment(UUID proposalId, UUID predecessorId) {
        List<ContinuationRow> rows = jdbc.query("SELECT id, code_access_confirmed, data_access_confirmed, access_notes, revision_number, recorded_at FROM proposal_continuation_revisions WHERE proposal_id=? AND predecessor_study_id=? ORDER BY revision_number DESC LIMIT 1",
                (result, index) -> new ContinuationRow(result.getBytes(1), result.getBoolean(2), result.getBoolean(3),
                        result.getString(4), result.getLong(5), result.getTimestamp(6).toInstant()),
                bytes(proposalId), bytes(predecessorId));
        if (rows.isEmpty()) {
            return new ContinuationAssessment(proposalId, predecessorId, AssessmentStatus.UNASSESSED,
                    null, null, null, null, null, null, false);
        }
        ContinuationRow row = rows.getFirst();
        int objectives = jdbc.queryForObject("SELECT COUNT(*) FROM proposal_objectives WHERE proposal_id=?",
                Integer.class, bytes(proposalId));
        int mapped = jdbc.queryForObject("SELECT COUNT(DISTINCT proposal_objective_id) FROM proposal_continuation_revision_links WHERE revision_id=?",
                Integer.class, row.id());
        Double coverage = objectives == 0 ? null : mapped * 100.0 / objectives;
        AssessmentStatus state = coverage == null ? AssessmentStatus.PARTIAL : AssessmentStatus.ASSESSED;
        boolean ready = state == AssessmentStatus.ASSESSED && coverage >= 60 && row.code() && row.data();
        return new ContinuationAssessment(proposalId, predecessorId, state, coverage, row.code(), row.data(),
                row.notes(), row.revision(), row.at(), ready);
    }

    private byte[] actorId(String email) {
        if (email == null || email.isBlank()) return jdbc.queryForObject("SELECT id FROM user_accounts ORDER BY created_at LIMIT 1", byte[].class);
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?)", byte[].class, email);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }
    static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }

    public record ObjectiveLink(UUID proposalObjectiveId, UUID continuationItemId, String rationale) {}
    public record ContinuationAssessment(UUID proposalId, UUID predecessorStudyId, AssessmentStatus state,
            Double objectiveCoverage, Boolean codeAccessConfirmed, Boolean dataAccessConfirmed, String accessNotes, Long revisionNumber,
            Instant recordedAt, boolean ready) {}
    public record ImprovementClaim(UUID id, UUID proposalId, UUID predecessorStudyId, UUID continuationItemId,
            String claim, String baseline, String target, String evaluationMethod, String recordedBy, Instant recordedAt) {}
    public record RouteAssessment(AssessmentStatus continuationState, Double continuationCoverage, Boolean codeAccess,
            Boolean dataAccess, AssessmentStatus improvementState, Integer improvementClaimCount) {
        public boolean continuationReady() {
            return continuationState == AssessmentStatus.ASSESSED && continuationCoverage != null
                    && continuationCoverage >= 60 && Boolean.TRUE.equals(codeAccess) && Boolean.TRUE.equals(dataAccess);
        }

        public boolean improvementReady() {
            return improvementState == AssessmentStatus.ASSESSED && improvementClaimCount != null
                    && improvementClaimCount > 0;
        }
    }
    private record ContinuationRow(byte[] id, boolean code, boolean data, String notes, long revision, Instant at) {}
}

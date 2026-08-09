package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.PlatformModels.ChangeDecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.ChangeOperation;
import com.ugnay.platform.shared.PlatformModels.ChangeOperationType;
import com.ugnay.platform.shared.PlatformModels.ContinuationClaimOutcome;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowActionService {
    private final JdbcTemplate jdbc;
    private final RoutingEvidenceRepository routing;
    private final JdbcAuditService audit;

    public WorkflowActionService(JdbcTemplate jdbc, RoutingEvidenceRepository routing, JdbcAuditService audit) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.audit = audit;
    }

    @Transactional
    public RoutingEvidenceRepository.ContinuationAssessment continuationEvidence(UUID proposalId, UUID predecessorId,
            List<RoutingEvidenceRepository.ObjectiveLink> links, boolean codeAccess, boolean dataAccess,
            String notes, String actor) {
        var result = routing.saveContinuation(proposalId, predecessorId, links, codeAccess, dataAccess, notes, actor);
        audit.append(actor, "CONTINUATION_EVIDENCE_RECORDED", "PROPOSAL", proposalId,
                "Recorded objective mappings and predecessor access evidence.",
                Map.of("predecessorStudyId", predecessorId.toString(), "objectiveCoverage", result.objectiveCoverage()));
        return result;
    }

    @Transactional
    public RoutingEvidenceRepository.ImprovementClaim improvementClaim(UUID proposalId, UUID predecessorId,
            UUID continuationItemId, String claim, String baseline, String target, String method, String actor) {
        var result = routing.saveImprovement(proposalId, predecessorId, continuationItemId, claim, baseline, target, method, actor);
        audit.append(actor, "IMPROVEMENT_CLAIM_RECORDED", "PROPOSAL", proposalId,
                "Recorded measurable predecessor-improvement evidence.", Map.of("claimId", result.id().toString()));
        return result;
    }

    @Transactional
    public AdviserRecommendation adviserRecommendation(UUID proposalId, UUID discoveryRunId,
            Recommendation recommendation, String rationale, String actor) {
        if (!Set.of(Recommendation.NEW, Recommendation.IMPROVE, Recommendation.CONTINUE,
                Recommendation.POSSIBLE_DUPLICATE, Recommendation.REVIEW_REQUIRED).contains(recommendation)) {
            throw new IllegalArgumentException("Unsupported adviser recommendation.");
        }
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM discovery_runs WHERE id=? AND proposal_id=?",
                Integer.class, bytes(discoveryRunId), bytes(proposalId));
        if (valid == null || valid == 0) throw new IllegalArgumentException("The frozen discovery run does not belong to this proposal.");
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        jdbc.update("INSERT INTO adviser_recommendations(id, proposal_id, discovery_run_id, recommendation, rationale, adviser_id, recorded_at) VALUES(?,?,?,?,?,?,?)",
                bytes(id), bytes(proposalId), bytes(discoveryRunId), recommendation.name(), required(rationale, "Rationale"),
                actorId(actor), Timestamp.from(at));
        audit.append(actor, "ADVISER_RECOMMENDATION_RECORDED", "PROPOSAL", proposalId,
                "Recorded an immutable adviser recommendation against a frozen discovery run.",
                Map.of("recommendationId", id.toString(), "recommendation", recommendation.name()));
        return new AdviserRecommendation(id, proposalId, discoveryRunId, recommendation, rationale.strip(), actor, at);
    }

    public List<AdviserRecommendation> adviserRecommendations(UUID proposalId) {
        return jdbc.query("SELECT a.id,a.proposal_id,a.discovery_run_id,a.recommendation,a.rationale,u.email,a.recorded_at FROM adviser_recommendations a JOIN user_accounts u ON u.id=a.adviser_id WHERE a.proposal_id=? ORDER BY a.recorded_at",
                (row, index) -> new AdviserRecommendation(uuid(row.getBytes(1)), uuid(row.getBytes(2)), uuid(row.getBytes(3)),
                        Recommendation.valueOf(row.getString(4)), row.getString(5), row.getString(6), row.getTimestamp(7).toInstant()),
                bytes(proposalId));
    }

    @Transactional
    public FindingAction findingAction(UUID projectId, Finding finding, FindingState state, String rationale,
            Instant expiresAt, String actor) {
        if (!Set.of(FindingState.RESOLVED, FindingState.ACCEPTED, FindingState.REOPENED).contains(state)) {
            throw new IllegalArgumentException("Finding actions must resolve, accept, or reopen a finding.");
        }
        if (state == FindingState.ACCEPTED && expiresAt == null) throw new IllegalArgumentException("Accepted exceptions require an expiry.");
        if (state != FindingState.ACCEPTED && expiresAt != null) throw new IllegalArgumentException("Only accepted exceptions have an expiry.");
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        String fingerprint = fingerprint(finding);
        jdbc.update("INSERT INTO finding_actions(id,project_id,finding_fingerprint,finding_code,action_state,rationale,expires_at,acted_by,acted_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(projectId), fingerprint, finding.code(), state.name(), required(rationale, "Finding action rationale"),
                expiresAt == null ? null : Timestamp.from(expiresAt), actorId(actor), Timestamp.from(at));
        audit.append(actor, "FINDING_" + state.name(), "FINDING", finding.id(), "Recorded a durable finding disposition.",
                Map.of("projectId", projectId.toString(), "fingerprint", fingerprint));
        return new FindingAction(id, projectId, fingerprint, finding.code(), state, rationale.strip(), expiresAt, actor, at);
    }

    public FindingState effectiveFindingState(UUID projectId, Finding finding) {
        List<FindingAction> actions = jdbc.query("SELECT a.id,a.project_id,a.finding_fingerprint,a.finding_code,a.action_state,a.rationale,a.expires_at,u.email,a.acted_at FROM finding_actions a JOIN user_accounts u ON u.id=a.acted_by WHERE a.project_id=? AND a.finding_fingerprint=? ORDER BY a.acted_at DESC LIMIT 1",
                (row, index) -> findingAction(row), bytes(projectId), fingerprint(finding));
        if (actions.isEmpty()) return finding.state();
        FindingAction action = actions.getFirst();
        if (action.state() == FindingState.RESOLVED) {
            List<Timestamp> occurrences = jdbc.query("SELECT ar.completed_at FROM findings f JOIN analysis_runs ar ON ar.id=f.analysis_run_id WHERE f.project_id=? AND f.id=? AND ar.completed_at IS NOT NULL",
                    (row, index) -> row.getTimestamp(1), bytes(projectId), bytes(finding.id()));
            if (!occurrences.isEmpty() && occurrences.getFirst().toInstant().isAfter(action.actedAt())) return FindingState.REOPENED;
        }
        if (action.state() == FindingState.ACCEPTED && action.expiresAt() != null && !action.expiresAt().isAfter(Instant.now())) {
            return FindingState.REOPENED;
        }
        return action.state();
    }

    @Transactional
    public ChangeOperation addChangeOperation(UUID changeRequestId, ChangeOperationType type, UUID targetItemId,
            TraceItemType itemType, String itemKey, String title, String description, String priority,
            String acceptanceCriteria, String verificationMethod, UUID sourceItemId, UUID linkTargetItemId,
            String relationshipType, boolean removeRelationship, String rationale, String actor) {
        String status = jdbc.queryForObject("SELECT request_status FROM change_requests WHERE id=?", String.class, bytes(changeRequestId));
        if (!Set.of("DRAFT", "IMPACT_REVIEW", "RETURNED_FOR_REVISION").contains(status)) {
            throw new IllegalArgumentException("Operations cannot be changed after a final change decision.");
        }
        int order = jdbc.queryForObject("SELECT COUNT(*)+1 FROM change_operations WHERE change_request_id=?", Integer.class, bytes(changeRequestId));
        validateOperation(type, targetItemId, itemType, itemKey, sourceItemId, linkTargetItemId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO change_operations(id,change_request_id,operation_order,operation_type,target_item_id,item_type,item_key,title,description,priority,acceptance_criteria,verification_method,source_item_id,link_target_item_id,relationship_type,remove_relationship,rationale) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(changeRequestId), order, type.name(), nullable(targetItemId), itemType == null ? null : itemType.name(),
                clean(itemKey), clean(title), clean(description), clean(priority), clean(acceptanceCriteria), clean(verificationMethod),
                nullable(sourceItemId), nullable(linkTargetItemId), clean(relationshipType), removeRelationship, required(rationale, "Operation rationale"));
        audit.append(actor, "CHANGE_OPERATION_ADDED", "CHANGE_REQUEST", changeRequestId,
                "Added a typed, baseline-bound change operation.", Map.of("operationId", id.toString(), "type", type.name()));
        return new ChangeOperation(id, changeRequestId, order, type, targetItemId, itemType, clean(itemKey), clean(title),
                clean(description), clean(priority), clean(acceptanceCriteria), clean(verificationMethod), sourceItemId,
                linkTargetItemId, clean(relationshipType), removeRelationship, rationale.strip());
    }

    public List<ChangeOperation> changeOperations(UUID changeRequestId) {
        return jdbc.query("SELECT id,change_request_id,operation_order,operation_type,target_item_id,item_type,item_key,title,description,priority,acceptance_criteria,verification_method,source_item_id,link_target_item_id,relationship_type,remove_relationship,rationale FROM change_operations WHERE change_request_id=? ORDER BY operation_order",
                (row, index) -> new ChangeOperation(uuid(row.getBytes(1)), uuid(row.getBytes(2)), row.getInt(3),
                        ChangeOperationType.valueOf(row.getString(4)), uuidOrNull(row.getBytes(5)),
                        row.getString(6) == null ? null : TraceItemType.valueOf(row.getString(6)), row.getString(7), row.getString(8),
                        row.getString(9), row.getString(10), row.getString(11), row.getString(12), uuidOrNull(row.getBytes(13)),
                        uuidOrNull(row.getBytes(14)), row.getString(15), row.getBoolean(16), row.getString(17)), bytes(changeRequestId));
    }

    @Transactional
    public ChangeDecision changeDecision(UUID requestId, ChangeDecisionDisposition disposition, String rationale,
            UUID baselineId, String actor) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        jdbc.update("INSERT INTO change_decision_history(id,change_request_id,disposition,rationale,decided_by,decided_at,resulting_baseline_id) VALUES(?,?,?,?,?,?,?)",
                bytes(id), bytes(requestId), disposition.name(), required(rationale, "Change decision rationale"), actorId(actor),
                Timestamp.from(at), nullable(baselineId));
        audit.append(actor, "CHANGE_" + disposition.name(), "CHANGE_REQUEST", requestId,
                "Recorded an immutable change decision.", Map.of("decisionId", id.toString()));
        return new ChangeDecision(id, requestId, disposition, rationale.strip(), actor, at, baselineId);
    }

    @Transactional
    public ContinuationClaim createClaim(UUID projectId, UUID continuationItemId, UUID objectiveId,
            String rationale, String actor) {
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM trace_items t JOIN continuation_items c ON c.id=? WHERE t.id=? AND t.project_id=? AND t.item_type='OBJECTIVE' AND c.item_status='OPEN'",
                Integer.class, bytes(continuationItemId), bytes(objectiveId), bytes(projectId));
        if (valid == null || valid == 0) throw new IllegalArgumentException("Claims must map an open predecessor item to a successor objective.");
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        jdbc.update("INSERT INTO continuation_item_claims(id,project_id,continuation_item_id,claimed_by,claim_rationale,claim_status,claimed_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
                bytes(id), bytes(projectId), bytes(continuationItemId), actorId(actor), required(rationale, "Claim rationale"), Timestamp.from(at));
        jdbc.update("INSERT INTO continuation_claim_objectives(claim_id,successor_objective_id) VALUES(?,?)", bytes(id), bytes(objectiveId));
        audit.append(actor, "CONTINUATION_ITEM_CLAIMED", "CONTINUATION_CLAIM", id,
                "Claimed predecessor work without rewriting predecessor history.", Map.of("projectId", projectId.toString()));
        return new ContinuationClaim(id, projectId, continuationItemId, objectiveId, "ACTIVE", rationale.strip(), actor, at);
    }

    @Transactional
    public ClaimEvent appendClaimOutcome(UUID claimId, ContinuationClaimOutcome outcome, String summary,
            UUID evidenceDocumentId, UUID evidenceTraceItemId, String actor) {
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM continuation_item_claims WHERE id=?", Integer.class, bytes(claimId));
        if (valid == null || valid == 0) throw new IllegalArgumentException("Continuation claim was not found.");
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        jdbc.update("INSERT INTO continuation_claim_events(id,claim_id,outcome_status,outcome_summary,evidence_document_id,evidence_trace_item_id,recorded_by,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                bytes(id), bytes(claimId), outcome.name(), required(summary, "Outcome summary"), nullable(evidenceDocumentId),
                nullable(evidenceTraceItemId), actorId(actor), Timestamp.from(at));
        jdbc.update("UPDATE continuation_item_claims SET claim_status=? WHERE id=?", outcome.name(), bytes(claimId));
        audit.append(actor, "CONTINUATION_OUTCOME_RECORDED", "CONTINUATION_CLAIM", claimId,
                "Appended a successor outcome while preserving earlier events.", Map.of("outcome", outcome.name()));
        return new ClaimEvent(id, claimId, outcome, summary.strip(), evidenceDocumentId, evidenceTraceItemId, actor, at);
    }

    public UUID claimProjectId(UUID claimId) {
        byte[] value = jdbc.queryForObject("SELECT project_id FROM continuation_item_claims WHERE id=?", byte[].class, bytes(claimId));
        if (value == null) throw new IllegalArgumentException("Continuation claim was not found.");
        return uuid(value);
    }

    private FindingAction findingAction(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp expires = row.getTimestamp(7);
        return new FindingAction(uuid(row.getBytes(1)), uuid(row.getBytes(2)), row.getString(3), row.getString(4),
                FindingState.valueOf(row.getString(5)), row.getString(6), expires == null ? null : expires.toInstant(),
                row.getString(8), row.getTimestamp(9).toInstant());
    }

    private static void validateOperation(ChangeOperationType type, UUID target, TraceItemType itemType, String key,
            UUID source, UUID linkTarget) {
        if (type == null) throw new IllegalArgumentException("Change operation type is required.");
        if (type == ChangeOperationType.ADD && (itemType == null || key == null || key.isBlank()))
            throw new IllegalArgumentException("ADD requires an item type and key.");
        if (Set.of(ChangeOperationType.REVISE, ChangeOperationType.RETIRE).contains(type) && target == null)
            throw new IllegalArgumentException(type + " requires a target item.");
        if (type == ChangeOperationType.RELINK && (source == null || linkTarget == null))
            throw new IllegalArgumentException("RELINK requires source and target items.");
    }

    public static String fingerprint(Finding finding) {
        try {
            String value = finding.code() + "|" + finding.implicatedItemIds().stream().sorted().toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private byte[] actorId(String email) { return jdbc.queryForObject("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?)", byte[].class, email); }
    private static String required(String value, String label) { if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required."); return value.strip(); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static byte[] nullable(UUID id) { return id == null ? null : bytes(id); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] value) { ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    private static UUID uuidOrNull(byte[] value) { return value == null ? null : uuid(value); }

    public record AdviserRecommendation(UUID id, UUID proposalId, UUID discoveryRunId, Recommendation recommendation,
            String rationale, String adviser, Instant recordedAt) {}
    public record FindingAction(UUID id, UUID projectId, String fingerprint, String code, FindingState state,
            String rationale, Instant expiresAt, String actor, Instant actedAt) {}
    public record ChangeDecision(UUID id, UUID changeRequestId, ChangeDecisionDisposition disposition, String rationale,
            String actor, Instant decidedAt, UUID resultingBaselineId) {}
    public record ContinuationClaim(UUID id, UUID projectId, UUID continuationItemId, UUID successorObjectiveId,
            String status, String rationale, String actor, Instant claimedAt) {}
    public record ClaimEvent(UUID id, UUID claimId, ContinuationClaimOutcome outcome, String summary,
            UUID evidenceDocumentId, UUID evidenceTraceItemId, String actor, Instant recordedAt) {}
}

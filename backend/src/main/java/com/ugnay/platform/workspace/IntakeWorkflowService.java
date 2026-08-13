package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.ProblemCase;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class IntakeWorkflowService {
    private static final Set<String> PRIVACY = Set.of("PUBLIC", "INTERNAL", "RESTRICTED");
    private static final Set<String> REFERENCE_TYPES = Set.of("DOCUMENT", "URL", "REPOSITORY", "OUTPUT", "TEST_RUN", "DATASET", "OTHER");

    private final WorkspaceService workspace;
    private final JdbcWorkspaceStore store;
    private final JdbcAuditService audit;

    public IntakeWorkflowService(WorkspaceService workspace, JdbcWorkspaceStore store, JdbcAuditService audit) {
        this.workspace = workspace;
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public synchronized IntakeResult submit(String idempotencyKey, IntakeCommand input, String actorEmail) {
        String key = required(idempotencyKey, "Idempotency-Key");
        if (key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 128 printable characters.");
        }
        IntakeCommand command = normalize(input);
        String requestHash = hash(command);
        var previous = store.intakeSubmission(actorEmail, key);
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!saved.requestSha256().equals(requestHash)) {
                throw new IdempotencyConflictException("This Idempotency-Key was already used for a different intake payload.");
            }
            return new IntakeResult(key, true, workspace.problem(saved.problemId()), workspace.proposal(saved.proposalId()),
                    workspace.discovery(saved.discoveryRunId()), store.evidenceReferences("PROBLEM_CASE", saved.problemId()));
        }

        Instant submittedAt = Instant.now();
        ProblemInput problemInput = command.problem();
        ProposalInput proposalInput = command.proposal();
        ProblemCase problem = workspace.createProblem(problemInput.title(), problemInput.problemStatement(),
                problemInput.stakeholder(), problemInput.affectedUsers(), problemInput.siteContext(),
                problemInput.desiredOutcome(), problemInput.constraints(), problemInput.privacyClassification(),
                command.evidenceReferences().size(), actorEmail, false);
        registerRollbackCleanup(problem.id());
        Proposal proposal = workspace.createProposal(problem.id(), proposalInput.title(), proposalInput.objectives(),
                proposalInput.proposedSolution(), proposalInput.methodology(), proposalInput.dataSources(),
                proposalInput.technology(), proposalInput.intendedUsers(), actorEmail);
        registerRollbackCleanup(problem.id(), proposal.id());
        DiscoveryRun discovery = workspace.runDiscovery(proposal, actorEmail);

        List<JdbcWorkspaceStore.EvidenceReferenceRecord> references = command.evidenceReferences().stream()
                .map(reference -> new JdbcWorkspaceStore.EvidenceReferenceRecord(UUID.randomUUID(), reference.type(),
                        reference.label(), reference.location(), reference.documentId(), reference.sha256(), "UNVERIFIED", submittedAt))
                .toList();
        store.saveEvidenceReferences("PROBLEM_CASE", problem.id(), references, actorEmail, submittedAt);
        store.saveIntakeSubmission(actorEmail, key, requestHash, problem.id(), proposal.id(), discovery.id(), submittedAt);
        audit.append(actorEmail, "INTAKE_SUBMITTED", "PROPOSAL", proposal.id(),
                "Atomically submitted a problem, proposal, evidence references, and frozen discovery run.",
                java.util.Map.of("problemId", problem.id().toString(), "discoveryRunId", discovery.id().toString(),
                        "evidenceReferenceCount", references.size()));
        return new IntakeResult(key, false, problem, proposal, discovery, references);
    }

    private IntakeCommand normalize(IntakeCommand value) {
        if (value == null || value.problem() == null || value.proposal() == null) {
            throw new IllegalArgumentException("Problem and proposal sections are required.");
        }
        ProblemInput p = value.problem();
        String privacy = required(p.privacyClassification(), "Privacy classification").toUpperCase(Locale.ROOT);
        if (!PRIVACY.contains(privacy)) throw new IllegalArgumentException("Privacy classification must be PUBLIC, INTERNAL, or RESTRICTED.");
        ProblemInput problem = new ProblemInput(required(p.title(), "Problem title"),
                requiredMinimum(p.problemStatement(), "Problem statement", 40), required(p.stakeholder(), "Stakeholder"),
                required(p.affectedUsers(), "Affected users"), required(p.siteContext(), "Site context"),
                required(p.desiredOutcome(), "Desired outcome"), clean(p.constraints()), privacy);

        ProposalInput q = value.proposal();
        List<String> objectives = q.objectives() == null ? List.of() : q.objectives().stream()
                .map(item -> required(item, "Objective")).toList();
        if (objectives.isEmpty()) throw new IllegalArgumentException("At least one measurable objective is required.");
        ProposalInput proposal = new ProposalInput(required(q.title(), "Proposal title"), objectives,
                required(q.proposedSolution(), "Proposed solution boundary"), clean(q.methodology()), clean(q.dataSources()),
                clean(q.technology()), clean(q.intendedUsers()));

        List<EvidenceReferenceInput> references = new ArrayList<>();
        for (EvidenceReferenceInput raw : value.evidenceReferences() == null ? List.<EvidenceReferenceInput>of() : value.evidenceReferences()) {
            String type = required(raw.type(), "Evidence reference type").toUpperCase(Locale.ROOT);
            if (!REFERENCE_TYPES.contains(type)) throw new IllegalArgumentException("Unsupported evidence reference type: " + type + ".");
            String location = clean(raw.location());
            if (location == null && raw.documentId() == null) {
                throw new IllegalArgumentException("Each evidence reference requires a location or stored document ID.");
            }
            String digest = clean(raw.sha256());
            if (digest != null && !digest.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Evidence SHA-256 values must contain exactly 64 hexadecimal characters.");
            }
            references.add(new EvidenceReferenceInput(type, required(raw.label(), "Evidence reference label"), location,
                    raw.documentId(), digest == null ? null : digest.toLowerCase(Locale.ROOT)));
        }
        return new IntakeCommand(problem, proposal, List.copyOf(references));
    }

    private void registerRollbackCleanup(UUID problemId, UUID... proposalIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    for (UUID proposalId : proposalIds) workspace.discardRolledBackIntake(problemId, proposalId);
                    if (proposalIds.length == 0) workspace.discardRolledBackIntake(problemId, null);
                }
            }
        });
    }

    private static String hash(IntakeCommand command) {
        StringBuilder value = new StringBuilder();
        append(value, command.problem().title()); append(value, command.problem().problemStatement());
        append(value, command.problem().stakeholder()); append(value, command.problem().affectedUsers());
        append(value, command.problem().siteContext()); append(value, command.problem().desiredOutcome());
        append(value, command.problem().constraints()); append(value, command.problem().privacyClassification());
        append(value, command.proposal().title()); append(value, command.proposal().proposedSolution());
        append(value, command.proposal().methodology()); append(value, command.proposal().dataSources());
        append(value, command.proposal().technology()); append(value, command.proposal().intendedUsers());
        command.proposal().objectives().forEach(item -> append(value, item));
        command.evidenceReferences().forEach(reference -> {
            append(value, reference.type()); append(value, reference.label()); append(value, reference.location());
            append(value, reference.documentId() == null ? null : reference.documentId().toString()); append(value, reference.sha256());
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static void append(StringBuilder target, String value) {
        String normalized = value == null ? "" : value;
        target.append(normalized.length()).append(':').append(normalized).append('|');
    }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
        return value.strip();
    }
    private static String requiredMinimum(String value, String label, int minimum) {
        String result = required(value, label);
        if (result.length() < minimum) throw new IllegalArgumentException(label + " must contain at least " + minimum + " characters.");
        return result;
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    public record IntakeCommand(ProblemInput problem, ProposalInput proposal, List<EvidenceReferenceInput> evidenceReferences) {}
    public record ProblemInput(String title, String problemStatement, String stakeholder, String affectedUsers,
            String siteContext, String desiredOutcome, String constraints, String privacyClassification) {}
    public record ProposalInput(String title, List<String> objectives, String proposedSolution, String methodology,
            String dataSources, String technology, String intendedUsers) {}
    public record EvidenceReferenceInput(String type, String label, String location, UUID documentId, String sha256) {}
    public record IntakeResult(String idempotencyKey, boolean replayed, ProblemCase problem, Proposal proposal,
            DiscoveryRun discovery, List<JdbcWorkspaceStore.EvidenceReferenceRecord> evidenceReferences) {}
}

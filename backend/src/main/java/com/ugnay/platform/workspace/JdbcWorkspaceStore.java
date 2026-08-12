package com.ugnay.platform.workspace;

import com.ugnay.platform.shared.PlatformModels.AssessmentStatus;
import com.ugnay.platform.shared.PlatformModels.CandidateEvidence;
import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.CompletionPackage;
import com.ugnay.platform.shared.PlatformModels.ComponentScore;
import com.ugnay.platform.shared.PlatformModels.ContinuityCriterion;
import com.ugnay.platform.shared.PlatformModels.ContinuationItem;
import com.ugnay.platform.shared.PlatformModels.Coverage;
import com.ugnay.platform.shared.PlatformModels.DecisionDisposition;
import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.DiscoveryRun;
import com.ugnay.platform.shared.PlatformModels.Finding;
import com.ugnay.platform.shared.PlatformModels.FindingState;
import com.ugnay.platform.shared.PlatformModels.HealthDimension;
import com.ugnay.platform.shared.PlatformModels.ImpactPreview;
import com.ugnay.platform.shared.PlatformModels.ImpactedArtifact;
import com.ugnay.platform.shared.PlatformModels.Lineage;
import com.ugnay.platform.shared.PlatformModels.LineageEdge;
import com.ugnay.platform.shared.PlatformModels.LineageNode;
import com.ugnay.platform.shared.PlatformModels.LineageType;
import com.ugnay.platform.shared.PlatformModels.ProblemCase;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ProjectHealth;
import com.ugnay.platform.shared.PlatformModels.ProjectStatus;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.ProposalDecision;
import com.ugnay.platform.shared.PlatformModels.Recommendation;
import com.ugnay.platform.shared.PlatformModels.ReviewQueueItem;
import com.ugnay.platform.shared.PlatformModels.ScopeRisk;
import com.ugnay.platform.shared.PlatformModels.Severity;
import com.ugnay.platform.shared.PlatformModels.Study;
import com.ugnay.platform.shared.PlatformModels.TestExecution;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import com.ugnay.platform.shared.PlatformModels.Traceability;
import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Relational persistence boundary for the evidence-chain workspace.
 *
 * <p>The API records are projections, not serialized aggregates. Every list that
 * carries domain meaning is persisted through an ordered child table. JSON
 * columns inherited from V1 receive only immutable configuration/snapshot
 * placeholders; they are never used to reconstruct relationships.</p>
 */
@Repository
public class JdbcWorkspaceStore {
    private final JdbcTemplate jdbc;

    public JdbcWorkspaceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEmpty() {
        return count("SELECT COUNT(*) FROM studies") == 0
                && count("SELECT COUNT(*) FROM problem_cases") == 0
                && count("SELECT COUNT(*) FROM projects") == 0;
    }

    @Transactional
    public void saveStudy(Study study) {
        byte[] departmentId = optionalDepartmentId(study.department());
        if (exists("studies", study.id())) {
            jdbc.update("UPDATE studies SET department_id=?, institutional_code=?, title=?, abstract_text=?, problem_statement=?, methodology=?, features_text=?, data_sources_text=?, technology_text=?, intended_users_text=?, stakeholders_text=?, site_context=?, keywords_text=?, academic_year=?, lifecycle_status=?, visibility=?, row_version=row_version+1 WHERE id=?",
                    departmentId, study.institutionalCode(), study.title(), study.abstractText(), study.problemStatement(),
                    study.methodology(), study.features(), study.dataSources(), study.technology(), study.intendedUsers(),
                    study.stakeholders(), study.siteContext(), String.join(", ", list(study.keywords())), study.academicYear(),
                    study.lifecycleStatus(), study.visibility(), bytes(study.id()));
        } else {
            jdbc.update("INSERT INTO studies(id, department_id, source_project_id, institutional_code, doi, title, abstract_text, problem_statement, methodology, features_text, data_sources_text, technology_text, intended_users_text, stakeholders_text, site_context, keywords_text, academic_year, lifecycle_status, visibility, repository_identifier, published_at, archived_at, row_version, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(study.id()), departmentId, null, study.institutionalCode(), null, study.title(), study.abstractText(),
                    study.problemStatement(), study.methodology(), study.features(), study.dataSources(), study.technology(),
                    study.intendedUsers(), study.stakeholders(), study.siteContext(), String.join(", ", list(study.keywords())),
                    study.academicYear(), study.lifecycleStatus(), study.visibility(), null, timestamp(Instant.now()), null, 0,
                    timestamp(Instant.now()));
        }
        jdbc.update("DELETE FROM study_objectives WHERE study_id=?", bytes(study.id()));
        for (int index = 0; index < list(study.objectives()).size(); index++) {
            jdbc.update("INSERT INTO study_objectives(id, study_id, objective_order, statement_text) VALUES(?,?,?,?)",
                    bytes(stableId("study-objective:" + study.id() + ":" + index)), bytes(study.id()), index, study.objectives().get(index));
        }
        jdbc.update("DELETE FROM study_terms WHERE study_id=?", bytes(study.id()));
        for (String keyword : list(study.keywords())) {
            UUID termId = stableId("keyword:" + keyword.strip().toLowerCase());
            if (!exists("taxonomy_terms", termId)) {
                jdbc.update("INSERT INTO taxonomy_terms(id, term_type, canonical_label, filipino_label, active) VALUES(?,?,?,?,?)",
                        bytes(termId), "KEYWORD", keyword.strip(), null, true);
            }
            jdbc.update("INSERT INTO study_terms(study_id, term_id) VALUES(?,?)", bytes(study.id()), bytes(termId));
        }
        Map<UUID, ContinuationItem> incoming = new LinkedHashMap<>();
        for (ContinuationItem item : list(study.continuationItems())) incoming.put(item.id(), item);
        for (ContinuationItem item : incoming.values()) {
            if (exists("continuation_items", item.id())) {
                jdbc.update("UPDATE continuation_items SET item_type=?, title=?, description=?, item_status=? WHERE id=? AND study_id=?",
                        item.type(), item.title(), item.description(), item.status(), bytes(item.id()), bytes(study.id()));
            } else {
                jdbc.update("INSERT INTO continuation_items(id, study_id, item_type, title, description, item_status, created_at) VALUES(?,?,?,?,?,?,?)",
                        bytes(item.id()), bytes(study.id()), item.type(), item.title(), item.description(), item.status(), timestamp(Instant.now()));
            }
        }
    }

    public List<Study> studies() {
        return jdbc.query("SELECT s.*, d.name AS department_name FROM studies s LEFT JOIN departments d ON d.id=s.department_id ORDER BY s.academic_year DESC, s.title",
                (result, row) -> new Study(
                        uuid(result.getBytes("id")), result.getString("institutional_code"), result.getString("title"),
                        result.getString("academic_year"), result.getString("department_name"), result.getString("lifecycle_status"),
                        result.getString("visibility"), result.getString("abstract_text"), result.getString("problem_statement"),
                        studyObjectives(result.getBytes("id")), studyKeywords(result.getBytes("id")), result.getString("methodology"),
                        result.getString("features_text"), result.getString("data_sources_text"), result.getString("technology_text"),
                        result.getString("intended_users_text"), result.getString("stakeholders_text"), result.getString("site_context"),
                        continuationItems(result.getBytes("id"))));
    }

    @Transactional
    public UUID saveCompletedStudy(Study study, UUID sourceProjectId) {
        List<UUID> existing = jdbc.query("SELECT id FROM studies WHERE source_project_id=?",
                (row, index) -> uuid(row.getBytes(1)), bytes(sourceProjectId));
        if (!existing.isEmpty()) return existing.getFirst();
        saveStudy(study);
        jdbc.update("UPDATE studies SET source_project_id=? WHERE id=? AND source_project_id IS NULL",
                bytes(sourceProjectId), bytes(study.id()));
        return study.id();
    }

    public Optional<UUID> completedStudyId(UUID sourceProjectId) {
        return jdbc.query("SELECT id FROM studies WHERE source_project_id=?",
                (row, index) -> uuid(row.getBytes(1)), bytes(sourceProjectId)).stream().findFirst();
    }

    @Transactional
    public void saveProblem(ProblemCase problem) {
        byte[] department = defaultDepartmentId();
        byte[] actor = defaultActorId();
        if (exists("problem_cases", problem.id())) {
            jdbc.update("UPDATE problem_cases SET title=?, problem_statement=?, stakeholder=?, affected_users=?, site_context=?, desired_outcome=?, constraints_text=?, privacy_classification=?, intake_status=?, row_version=?, updated_at=? WHERE id=?",
                    problem.title(), problem.problemStatement(), problem.stakeholder(), problem.affectedUsers(), problem.siteContext(),
                    problem.desiredOutcome(), problem.constraints(), problem.privacyClassification(), problem.status(), problem.rowVersion(),
                    timestamp(Instant.now()), bytes(problem.id()));
        } else {
            jdbc.update("INSERT INTO problem_cases(id, department_id, created_by, title, problem_statement, stakeholder, affected_users, site_context, desired_outcome, constraints_text, privacy_classification, intake_status, row_version, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(problem.id()), department, actor, problem.title(), problem.problemStatement(), problem.stakeholder(),
                    problem.affectedUsers(), problem.siteContext(), problem.desiredOutcome(), problem.constraints(),
                    problem.privacyClassification(), problem.status(), problem.rowVersion(), timestamp(problem.createdAt()), timestamp(problem.createdAt()));
        }
        int current = jdbc.queryForObject("SELECT COUNT(*) FROM problem_evidence WHERE problem_case_id=?", Integer.class, bytes(problem.id()));
        for (int index = current; index < problem.evidenceCount(); index++) {
            jdbc.update("INSERT INTO problem_evidence(id, problem_case_id, evidence_type, summary, document_id, created_at) VALUES(?,?,?,?,?,?)",
                    bytes(stableId("problem-evidence:" + problem.id() + ":" + index)), bytes(problem.id()), "RECORDED",
                    "Structured intake evidence " + (index + 1), null, timestamp(problem.createdAt()));
        }
    }

    public List<ProblemCase> problems() {
        return jdbc.query("SELECT p.*, (SELECT COUNT(*) FROM problem_evidence e WHERE e.problem_case_id=p.id) AS evidence_count FROM problem_cases p ORDER BY p.created_at DESC",
                (result, row) -> new ProblemCase(uuid(result.getBytes("id")), result.getString("title"),
                        result.getString("problem_statement"), result.getString("stakeholder"), result.getString("affected_users"),
                        result.getString("site_context"), result.getString("desired_outcome"), result.getString("constraints_text"),
                        result.getString("privacy_classification"), result.getString("intake_status"), result.getInt("evidence_count"),
                        instant(result, "created_at"), result.getLong("row_version")));
    }

    @Transactional
    public void saveProposal(Proposal proposal, UUID problemId) {
        byte[] actor = defaultActorId();
        if (exists("proposals", proposal.id())) {
            jdbc.update("UPDATE proposals SET problem_case_id=?, proposed_title=?, proposed_solution=?, methodology=?, technology_text=?, data_sources_text=?, intended_users_text=?, proposal_status=?, row_version=?, submitted_at=? WHERE id=?",
                    bytes(problemId), proposal.title(), proposal.proposedSolution(), proposal.methodology(), proposal.technology(),
                    proposal.dataSources(), proposal.intendedUsers(), proposal.status(), proposal.rowVersion(), timestamp(proposal.submittedAt()), bytes(proposal.id()));
        } else {
            jdbc.update("INSERT INTO proposals(id, problem_case_id, submitted_by, proposed_title, proposed_solution, methodology, technology_text, data_sources_text, intended_users_text, proposal_status, row_version, submitted_at, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(proposal.id()), bytes(problemId), actor, proposal.title(), proposal.proposedSolution(), proposal.methodology(),
                    proposal.technology(), proposal.dataSources(), proposal.intendedUsers(), proposal.status(), proposal.rowVersion(),
                    timestamp(proposal.submittedAt()), timestamp(proposal.submittedAt()));
        }
        jdbc.update("DELETE FROM proposal_objectives WHERE proposal_id=?", bytes(proposal.id()));
        for (int index = 0; index < list(proposal.objectives()).size(); index++) {
            jdbc.update("INSERT INTO proposal_objectives(id, proposal_id, objective_order, statement_text, novelty_rationale, baseline_measure, target_measure, evaluation_method) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(stableId("proposal-objective:" + proposal.id() + ":" + index)), bytes(proposal.id()), index,
                    proposal.objectives().get(index), null, null, null, null);
        }
    }

    public List<Proposal> proposals() {
        return jdbc.query("SELECT q.*, p.problem_statement, p.stakeholder, p.affected_users, p.site_context, p.desired_outcome, p.constraints_text, p.privacy_classification FROM proposals q JOIN problem_cases p ON p.id=q.problem_case_id ORDER BY q.submitted_at DESC",
                (result, row) -> new Proposal(uuid(result.getBytes("id")), result.getString("proposed_title"),
                        result.getString("problem_statement"), result.getString("stakeholder"), result.getString("affected_users"),
                        result.getString("site_context"), result.getString("desired_outcome"), result.getString("constraints_text"),
                        result.getString("privacy_classification"), proposalObjectives(result.getBytes("id")),
                        result.getString("proposed_solution"), result.getString("methodology"), result.getString("data_sources_text"),
                        result.getString("technology_text"), result.getString("intended_users_text"), result.getString("proposal_status"),
                        instant(result, "submitted_at"), result.getLong("row_version")));
    }

    public Map<UUID, UUID> proposalProblemIds() {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, problem_case_id FROM proposals",
                (row, index) -> Map.entry(uuid(row.getBytes(1)), uuid(row.getBytes(2))))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    @Transactional
    public void saveDiscovery(DiscoveryRun run) {
        UUID configId = stableId("algorithm:" + run.algorithmVersion());
        if (!exists("algorithm_configurations", configId)) {
            jdbc.update("INSERT INTO algorithm_configurations(id, algorithm_version, configuration_json, configuration_sha256, activated_at, retired_at) VALUES(?,?,?,?,?,?)",
                    bytes(configId), run.algorithmVersion(), "{}", sha256(run.algorithmVersion()), timestamp(run.createdAt()), null);
        }
        if (!exists("discovery_runs", run.id())) {
            jdbc.update("INSERT INTO discovery_runs(id, proposal_id, algorithm_configuration_id, input_sha256, assessment_status, recommendation, confidence_score, explanation, input_snapshot_json, started_at, completed_at, semantic_provider) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(run.id()), bytes(run.proposalId()), bytes(configId), run.inputHash(), run.assessmentStatus().name(),
                    run.recommendation().name(), run.confidence(), run.explanation(), "{}", timestamp(run.createdAt()),
                    timestamp(run.createdAt()), run.semanticProvider());
            for (int index = 0; index < list(run.revisionChecklist()).size(); index++) {
                jdbc.update("INSERT INTO discovery_revision_checklist(discovery_run_id, checklist_order, checklist_text) VALUES(?,?,?)",
                        bytes(run.id()), index, run.revisionChecklist().get(index));
            }
            for (DiscoveryCandidate candidate : list(run.candidates())) saveCandidate(run.id(), candidate);
        }
    }

    public List<DiscoveryRun> discoveryRuns() {
        return jdbc.query("SELECT r.*, c.algorithm_version FROM discovery_runs r JOIN algorithm_configurations c ON c.id=r.algorithm_configuration_id ORDER BY r.completed_at DESC",
                (result, row) -> new DiscoveryRun(uuid(result.getBytes("id")), uuid(result.getBytes("proposal_id")),
                        AssessmentStatus.valueOf(result.getString("assessment_status")), Recommendation.valueOf(result.getString("recommendation")),
                        result.getDouble("confidence_score"), result.getString("algorithm_version"), result.getString("input_sha256"),
                        result.getString("semantic_provider"), result.getString("explanation"), revisionChecklist(result.getBytes("id")),
                        discoveryCandidates(result.getBytes("id")), instant(result, "completed_at")));
    }

    @Transactional
    public void saveDecision(ProposalDecision decision) {
        if (exists("proposal_decisions", decision.id())) return;
        jdbc.update("INSERT INTO proposal_decisions(id, proposal_id, discovery_run_id, disposition, rationale, decided_by, decided_at) VALUES(?,?,?,?,?,?,?)",
                bytes(decision.id()), bytes(decision.proposalId()), nullableBytes(decision.discoveryRunId()), decision.disposition().name(),
                decision.rationale(), defaultActorId(), timestamp(decision.decidedAt()));
        if (decision.primaryPredecessorId() != null) {
            jdbc.update("INSERT INTO decision_target_studies(decision_id, study_id, relationship_type, primary_target) VALUES(?,?,?,?)",
                    bytes(decision.id()), bytes(decision.primaryPredecessorId()), dispositionRelationship(decision.disposition()), true);
        }
    }

    public List<ProposalDecision> decisions() {
        return jdbc.query("SELECT d.*, u.display_name, (SELECT t.study_id FROM decision_target_studies t WHERE t.decision_id=d.id AND t.primary_target=TRUE) AS predecessor_id FROM proposal_decisions d JOIN user_accounts u ON u.id=d.decided_by ORDER BY d.decided_at DESC",
                (result, row) -> new ProposalDecision(uuid(result.getBytes("id")), uuid(result.getBytes("proposal_id")),
                        nullableUuid(result.getBytes("discovery_run_id")), DecisionDisposition.valueOf(result.getString("disposition")),
                        result.getString("rationale"), result.getString("display_name"), instant(result, "decided_at"),
                        nullableUuid(result.getBytes("predecessor_id"))));
    }

    @Transactional
    public void saveProject(Project project, UUID proposalId) {
        byte[] department = departmentId(project.department());
        byte[] currentBaseline = baselineExists(project.currentBaselineId()) ? bytes(project.currentBaselineId()) : null;
        if (exists("projects", project.id())) {
            jdbc.update("UPDATE projects SET title=?, project_status=?, route_type=?, current_baseline_id=?, row_version=?, updated_at=?, completed_at=? WHERE id=?",
                    project.title(), project.status().name(), project.route().name(), currentBaseline, project.rowVersion(),
                    timestamp(project.updatedAt()), project.status() == ProjectStatus.COMPLETED ? timestamp(project.updatedAt()) : null,
                    bytes(project.id()));
        } else {
            jdbc.update("INSERT INTO projects(id, proposal_id, department_id, project_code, title, project_status, route_type, row_version, created_at, completed_at, current_baseline_id, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(project.id()), bytes(proposalId), department, project.code(), project.title(), project.status().name(),
                    project.route().name(), project.rowVersion(), timestamp(project.updatedAt()),
                    project.status() == ProjectStatus.COMPLETED ? timestamp(project.updatedAt()) : null, currentBaseline,
                    timestamp(project.updatedAt()));
        }
        jdbc.update("DELETE FROM project_team_members WHERE project_id=?", bytes(project.id()));
        for (int index = 0; index < list(project.team()).size(); index++) {
            jdbc.update("INSERT INTO project_team_members(project_id, member_order, display_name) VALUES(?,?,?)",
                    bytes(project.id()), index, project.team().get(index));
        }
    }

    public List<Project> projects() {
        return jdbc.query("SELECT p.*, d.name AS department_name, COALESCE(b.baseline_number, 0) AS baseline_number FROM projects p JOIN departments d ON d.id=p.department_id LEFT JOIN project_baselines b ON b.id=p.current_baseline_id ORDER BY p.updated_at DESC",
                (result, row) -> new Project(uuid(result.getBytes("id")), result.getString("project_code"), result.getString("title"),
                        ProjectStatus.valueOf(result.getString("project_status")), Recommendation.valueOf(result.getString("route_type")),
                        result.getString("department_name"), nullableUuid(result.getBytes("current_baseline_id")), result.getInt("baseline_number"),
                        projectTeam(result.getBytes("id")), instantOr(result, "updated_at", "created_at"), result.getLong("row_version")));
    }

    public Map<UUID, UUID> projectProposalIds() {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, proposal_id FROM projects",
                (row, index) -> Map.entry(uuid(row.getBytes(1)), uuid(row.getBytes(2))))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    @Transactional
    public void saveTraceability(Traceability trace) {
        if (trace.baselineId() == null) return;
        byte[] actor = defaultActorId();
        boolean freezeBaseline = !baselineExists(trace.baselineId());
        if (freezeBaseline) {
            jdbc.update("INSERT INTO project_baselines(id, project_id, baseline_number, baseline_status, approved_by, approval_rationale, approved_at, content_sha256, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    bytes(trace.baselineId()), bytes(trace.projectId()), trace.baselineNumber(), "APPROVED", actor,
                    "Persisted approved traceability baseline.", timestamp(Instant.now()), sha256(trace.toString()), timestamp(Instant.now()));
        }
        jdbc.update("UPDATE projects SET current_baseline_id=?, updated_at=? WHERE id=?",
                bytes(trace.baselineId()), timestamp(Instant.now()), bytes(trace.projectId()));

        for (TraceItem item : list(trace.items())) {
            if (exists("trace_items", item.id())) {
                jdbc.update("UPDATE trace_items SET item_key=?, item_type=?, lifecycle_status=?, current_revision=?, row_version=row_version+1 WHERE id=? AND project_id=?",
                        item.key(), item.type().name(), item.lifecycleStatus(), item.currentRevision(), bytes(item.id()), bytes(trace.projectId()));
            } else {
                jdbc.update("INSERT INTO trace_items(id, project_id, item_key, item_type, lifecycle_status, current_revision, row_version, created_at) VALUES(?,?,?,?,?,?,?,?)",
                        bytes(item.id()), bytes(trace.projectId()), item.key(), item.type().name(), item.lifecycleStatus(), item.currentRevision(), 0,
                        timestamp(Instant.now()));
            }
            UUID revisionId = stableId("trace-revision:" + item.id() + ":" + item.currentRevision());
            if (!exists("trace_item_revisions", revisionId)) {
                jdbc.update("INSERT INTO trace_item_revisions(id, trace_item_id, revision_number, title, description, priority_code, acceptance_criteria, verification_method, detail_json, revision_status, created_by, created_at, readiness_score) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        bytes(revisionId), bytes(item.id()), item.currentRevision(), item.title(), item.description(), item.priority(),
                        item.acceptanceCriteria(), item.verificationMethod(), "{}", item.lifecycleStatus(), actor, timestamp(Instant.now()),
                        item.readinessScore());
            } else {
                // A draft revision may be promoted exactly once when a new baseline is
                // approved. Its content remains unchanged; later edits create revision N+1.
                jdbc.update("UPDATE trace_item_revisions SET revision_status=?, readiness_score=? WHERE id=?",
                        item.lifecycleStatus(), item.readinessScore(), bytes(revisionId));
            }
            if (freezeBaseline && count("SELECT COUNT(*) FROM baseline_items WHERE baseline_id=? AND trace_item_id=?", bytes(trace.baselineId()), bytes(item.id())) == 0) {
                jdbc.update("INSERT INTO baseline_items(baseline_id, trace_item_id, trace_item_revision_id) VALUES(?,?,?)",
                        bytes(trace.baselineId()), bytes(item.id()), bytes(revisionId));
            }
        }
        for (TraceLink link : list(trace.links())) {
            if (exists("trace_links", link.id())) {
                jdbc.update("UPDATE trace_links SET link_status=?, rationale=?, row_version=row_version+1 WHERE id=? AND project_id=?",
                        link.status(), link.rationale(), bytes(link.id()), bytes(trace.projectId()));
            } else {
                jdbc.update("INSERT INTO trace_links(id, project_id, source_item_id, target_item_id, link_type, link_status, rationale, row_version, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        bytes(link.id()), bytes(trace.projectId()), bytes(link.sourceId()), bytes(link.targetId()), link.type(), link.status(),
                        link.rationale(), 0, timestamp(Instant.now()));
            }
            if (freezeBaseline && count("SELECT COUNT(*) FROM baseline_links WHERE baseline_id=? AND trace_link_id=?", bytes(trace.baselineId()), bytes(link.id())) == 0) {
                jdbc.update("INSERT INTO baseline_links(baseline_id, trace_link_id) VALUES(?,?)", bytes(trace.baselineId()), bytes(link.id()));
            }
        }
        for (TestExecution execution : list(trace.executions())) {
            if (exists("test_executions", execution.id())) {
                jdbc.update("UPDATE test_executions SET execution_status=?, build_identifier=?, is_current=?, evidence_confirmed=?, executed_at=? WHERE id=?",
                        execution.status(), execution.buildIdentifier(), execution.current(), execution.hasEvidence(), timestamp(execution.executedAt()), bytes(execution.id()));
            } else {
                jdbc.update("INSERT INTO test_executions(id, project_id, test_item_id, baseline_id, build_identifier, execution_status, evidence_document_id, is_current, executed_by, executed_at, evidence_confirmed) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        bytes(execution.id()), bytes(trace.projectId()), bytes(execution.testItemId()), bytes(trace.baselineId()), execution.buildIdentifier(),
                        execution.status(), null, execution.current(), actor, timestamp(execution.executedAt()), execution.hasEvidence());
            }
        }
        saveAnalysisSnapshot(trace);
    }

    public List<Traceability> traceability() {
        List<Traceability> result = new ArrayList<>();
        for (Project project : projects()) {
            if (project.currentBaselineId() == null) continue;
            byte[] projectId = bytes(project.id());
            byte[] baselineId = bytes(project.currentBaselineId());
            List<TraceItem> items = jdbc.query("SELECT i.*, r.title, r.description, r.priority_code, r.acceptance_criteria, r.verification_method, r.readiness_score FROM trace_items i JOIN trace_item_revisions r ON r.trace_item_id=i.id AND r.revision_number=i.current_revision WHERE i.project_id=? ORDER BY i.item_key",
                    (row, index) -> new TraceItem(uuid(row.getBytes("id")), row.getString("item_key"), TraceItemType.valueOf(row.getString("item_type")),
                            row.getString("title"), row.getString("description"), row.getString("lifecycle_status"), row.getString("priority_code"),
                            row.getString("acceptance_criteria"), row.getString("verification_method"), row.getInt("current_revision"),
                            row.getDouble("readiness_score")), projectId);
            List<TraceLink> links = jdbc.query("SELECT l.* FROM trace_links l WHERE l.project_id=? ORDER BY l.created_at, l.id",
                    (row, index) -> new TraceLink(uuid(row.getBytes("id")), uuid(row.getBytes("source_item_id")),
                            uuid(row.getBytes("target_item_id")), row.getString("link_type"), row.getString("link_status"), row.getString("rationale")),
                    projectId);
            List<TestExecution> executions = jdbc.query("SELECT * FROM test_executions WHERE project_id=? ORDER BY executed_at",
                    (row, index) -> new TestExecution(uuid(row.getBytes("id")), uuid(row.getBytes("test_item_id")),
                            row.getString("execution_status"), row.getString("build_identifier"), row.getBoolean("is_current"),
                            row.getBoolean("evidence_confirmed") || row.getBytes("evidence_document_id") != null, instant(row, "executed_at")), projectId);
            result.add(new Traceability(project.id(), project.currentBaselineId(), project.baselineNumber(),
                    latestTraceStatus(project.id()).orElse(AssessmentStatus.UNASSESSED), items, links, executions,
                    latestFindings(project.id()), latestCoverage(project.id(), project.currentBaselineId())));
        }
        return List.copyOf(result);
    }

    @Transactional
    public void saveScopeRisk(UUID projectId, UUID baselineId, ScopeRisk risk, Instant calculatedAt) {
        insertScopeRisk(projectId, baselineId, risk, calculatedAt);
    }

    public Map<UUID, ScopeRisk> scopeRisks() {
        Map<UUID, ScopeRisk> result = new LinkedHashMap<>();
        for (Project project : projects()) {
            latestScopeRisk(project.id()).ifPresent(risk -> result.put(project.id(), risk));
        }
        return Map.copyOf(result);
    }

    @Transactional
    public void saveChange(ChangeRequest change) {
        if (!exists("change_requests", change.id())) {
            jdbc.update("INSERT INTO change_requests(id, project_id, based_on_baseline_id, title, rationale, request_status, boundary_flags_json, requested_by, row_version, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    bytes(change.id()), bytes(change.projectId()), bytes(change.basedOnBaselineId()), change.title(), change.rationale(),
                    change.status(), "[]", defaultActorId(), change.rowVersion(), timestamp(change.createdAt()));
            for (int index = 0; index < list(change.changedItemIds()).size(); index++) {
                UUID itemId = change.changedItemIds().get(index);
                jdbc.update("INSERT INTO change_request_items(id, change_request_id, trace_item_id, operation_type, proposed_revision_json) VALUES(?,?,?,?,?)",
                        bytes(stableId("change-item:" + change.id() + ":" + index)), bytes(change.id()), bytes(itemId), "MODIFY", "{}");
            }
            for (int index = 0; index < list(change.boundaryFlags()).size(); index++) {
                jdbc.update("INSERT INTO change_request_boundary_flags(change_request_id, flag_order, boundary_flag) VALUES(?,?,?)",
                        bytes(change.id()), index, change.boundaryFlags().get(index));
            }
        } else {
            jdbc.update("UPDATE change_requests SET title=?, rationale=?, request_status=?, row_version=? WHERE id=?",
                    change.title(), change.rationale(), change.status(), change.rowVersion(), bytes(change.id()));
        }
    }

    public List<ChangeRequest> changes() {
        return jdbc.query("SELECT * FROM change_requests ORDER BY created_at DESC", (row, index) -> {
            byte[] id = row.getBytes("id");
            List<UUID> changed = jdbc.query("SELECT trace_item_id FROM change_request_items WHERE change_request_id=? AND trace_item_id IS NOT NULL ORDER BY id",
                    (child, childIndex) -> uuid(child.getBytes(1)), id);
            List<String> flags = jdbc.query("SELECT boundary_flag FROM change_request_boundary_flags WHERE change_request_id=? ORDER BY flag_order",
                    (child, childIndex) -> child.getString(1), id);
            return new ChangeRequest(uuid(id), uuid(row.getBytes("project_id")), uuid(row.getBytes("based_on_baseline_id")),
                    row.getString("title"), row.getString("rationale"), row.getString("request_status"), changed, flags,
                    instant(row, "created_at"), row.getLong("row_version"));
        });
    }

    @Transactional
    public void saveImpact(ImpactPreview preview) {
        byte[] changeId = bytes(preview.changeRequestId());
        List<byte[]> previous = jdbc.query("SELECT id FROM impact_paths WHERE change_request_id=?", (row, index) -> row.getBytes(1), changeId);
        for (byte[] pathId : previous) jdbc.update("DELETE FROM impact_path_nodes WHERE impact_path_id=?", pathId);
        List<byte[]> previews = jdbc.query("SELECT id FROM impact_previews WHERE change_request_id=?", (row, index) -> row.getBytes(1), changeId);
        for (byte[] previewId : previews) jdbc.update("DELETE FROM impact_documents_to_revise WHERE impact_preview_id=?", previewId);
        jdbc.update("DELETE FROM impact_paths WHERE change_request_id=?", changeId);
        jdbc.update("DELETE FROM impact_previews WHERE change_request_id=?", changeId);

        UUID projectId = jdbc.queryForObject("SELECT project_id FROM change_requests WHERE id=?", (row, index) -> uuid(row.getBytes(1)), changeId);
        UUID riskId = insertScopeRisk(projectId, preview.basedOnBaselineId(), preview.scopeRisk(), preview.calculatedAt());
        UUID previewId = stableId("impact-preview:" + preview.changeRequestId() + ":" + preview.calculatedAt());
        jdbc.update("INSERT INTO impact_previews(id, change_request_id, based_on_baseline_id, baseline_current, scope_risk_snapshot_id, calculated_at) VALUES(?,?,?,?,?,?)",
                bytes(previewId), changeId, bytes(preview.basedOnBaselineId()), preview.baselineCurrent(), bytes(riskId), timestamp(preview.calculatedAt()));
        for (int artifactIndex = 0; artifactIndex < list(preview.impactedArtifacts()).size(); artifactIndex++) {
            ImpactedArtifact artifact = preview.impactedArtifacts().get(artifactIndex);
            UUID pathId = stableId("impact-path:" + previewId + ":" + artifactIndex);
            UUID sourceId = artifact.path().isEmpty() ? artifact.itemId() : artifact.path().getFirst();
            jdbc.update("INSERT INTO impact_paths(id, change_request_id, source_item_id, impacted_item_id, path_json, hop_count, severity, evidence_becomes_stale, calculated_at, reason_text) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    bytes(pathId), changeId, bytes(sourceId), bytes(artifact.itemId()), "[]", artifact.hopCount(), artifact.severity().name(),
                    artifact.evidenceBecomesStale(), timestamp(preview.calculatedAt()), artifact.reason());
            for (int nodeIndex = 0; nodeIndex < list(artifact.path()).size(); nodeIndex++) {
                jdbc.update("INSERT INTO impact_path_nodes(impact_path_id, node_order, trace_item_id) VALUES(?,?,?)",
                        bytes(pathId), nodeIndex, bytes(artifact.path().get(nodeIndex)));
            }
        }
        for (int index = 0; index < list(preview.documentsToRevise()).size(); index++) {
            jdbc.update("INSERT INTO impact_documents_to_revise(impact_preview_id, document_order, document_label) VALUES(?,?,?)",
                    bytes(previewId), index, preview.documentsToRevise().get(index));
        }
    }

    public Map<UUID, ImpactPreview> impactPreviews() {
        Map<UUID, ImpactPreview> result = new LinkedHashMap<>();
        jdbc.query("SELECT p.*, c.project_id FROM impact_previews p JOIN change_requests c ON c.id=p.change_request_id ORDER BY p.calculated_at",
                row -> {
                    UUID previewId = uuid(row.getBytes("id"));
                    UUID changeId = uuid(row.getBytes("change_request_id"));
                    UUID projectId = uuid(row.getBytes("project_id"));
                    List<ImpactedArtifact> artifacts = impactArtifacts(changeId);
                    List<String> documents = jdbc.query("SELECT document_label FROM impact_documents_to_revise WHERE impact_preview_id=? ORDER BY document_order",
                            (child, index) -> child.getString(1), bytes(previewId));
                    ScopeRisk risk = scopeRiskById(uuid(row.getBytes("scope_risk_snapshot_id")));
                    result.put(changeId, new ImpactPreview(changeId, uuid(row.getBytes("based_on_baseline_id")),
                            row.getBoolean("baseline_current"), risk, artifacts, documents, instant(row, "calculated_at")));
                });
        return Map.copyOf(result);
    }

    @Transactional
    public void saveCompletion(CompletionPackage completion) {
        byte[] id = bytes(completion.id());
        boolean complete = "COMPLETE".equals(completion.status());
        byte[] completedBy = complete ? defaultActorId() : null;
        Timestamp completedAt = complete ? timestamp(Instant.now()) : null;
        if (exists("completion_packages", completion.id())) {
            jdbc.update("UPDATE completion_packages SET package_status=?, readiness_score=?, code_data_rights_confirmed=?, completed_by=?, completed_at=?, row_version=row_version+1 WHERE id=? AND project_id=?",
                    completion.status(), completion.readinessScore(), completion.codeDataRightsConfirmed(), completedBy, completedAt,
                    id, bytes(completion.projectId()));
        } else {
            jdbc.update("INSERT INTO completion_packages(id, project_id, package_status, readiness_score, code_data_rights_confirmed, ownership_notes, contact_path, completed_by, completed_at, row_version, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id, bytes(completion.projectId()), completion.status(), completion.readinessScore(), completion.codeDataRightsConfirmed(),
                    null, null, completedBy, completedAt, 0, timestamp(Instant.now()));
        }
        jdbc.update("DELETE FROM completion_criteria WHERE completion_package_id=?", id);
        for (int index = 0; index < list(completion.criteria()).size(); index++) {
            ContinuityCriterion criterion = completion.criteria().get(index);
            jdbc.update("INSERT INTO completion_criteria(completion_package_id, criterion_order, criterion_key, criterion_label, criterion_weight, completion_ratio, explanation) VALUES(?,?,?,?,?,?,?)",
                    id, index, criterion.key(), criterion.label(), criterion.weight(), criterion.completion(), criterion.explanation());
        }
        jdbc.update("DELETE FROM completion_package_items WHERE completion_package_id=?", id);
        saveCompletionItems(id, "BLOCKER", completion.blockers());
        saveCompletionItems(id, "LIMITATION", completion.limitations());
        saveCompletionItems(id, "RECOMMENDATION", completion.recommendations());
        saveCompletionItems(id, "UNFINISHED_WORK", completion.unfinishedWork());

        List<byte[]> repositories = jdbc.query("SELECT id FROM repository_references WHERE project_id=? ORDER BY id", (row, index) -> row.getBytes(1), bytes(completion.projectId()));
        if (repositories.isEmpty()) {
            jdbc.update("INSERT INTO repository_references(id, project_id, repository_url, commit_hash, release_tag, licence_name, access_status, setup_instructions, verified_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    bytes(stableId("repository:" + completion.projectId())), bytes(completion.projectId()), value(completion.repositoryUrl()),
                    value(completion.commitHash()), null, null, completion.codeDataRightsConfirmed() ? "CONFIRMED" : "PENDING",
                    value(completion.setupInstructions()), null);
        } else {
            jdbc.update("UPDATE repository_references SET repository_url=?, commit_hash=?, access_status=?, setup_instructions=? WHERE id=?",
                    value(completion.repositoryUrl()), value(completion.commitHash()), completion.codeDataRightsConfirmed() ? "CONFIRMED" : "PENDING",
                    value(completion.setupInstructions()), repositories.getFirst());
        }
    }

    public List<CompletionPackage> completionPackages() {
        return jdbc.query("SELECT * FROM completion_packages ORDER BY created_at", (row, index) -> {
            byte[] packageId = row.getBytes("id");
            UUID projectId = uuid(row.getBytes("project_id"));
            List<ContinuityCriterion> criteria = jdbc.query("SELECT * FROM completion_criteria WHERE completion_package_id=? ORDER BY criterion_order",
                    (child, childIndex) -> new ContinuityCriterion(child.getString("criterion_key"), child.getString("criterion_label"),
                            child.getInt("criterion_weight"), child.getDouble("completion_ratio"), child.getString("explanation")), packageId);
            RepositoryRow repository = repository(projectId);
            return new CompletionPackage(uuid(packageId), projectId, row.getString("package_status"), row.getDouble("readiness_score"),
                    row.getBoolean("code_data_rights_confirmed"), criteria, completionItems(packageId, "BLOCKER"),
                    repository.url(), repository.commit(), repository.setup(), completionItems(packageId, "LIMITATION"),
                    completionItems(packageId, "RECOMMENDATION"), completionItems(packageId, "UNFINISHED_WORK"));
        });
    }

    @Transactional
    public void saveLineage(Lineage lineage) {
        byte[] projectId = bytes(lineage.projectId());
        jdbc.update("DELETE FROM lineage_edges WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM lineage_nodes WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM project_predecessors WHERE project_id=?", projectId);
        for (int index = 0; index < list(lineage.nodes()).size(); index++) {
            LineageNode node = lineage.nodes().get(index);
            jdbc.update("INSERT INTO lineage_nodes(id, project_id, node_order, node_kind, title, node_status, academic_year, is_current) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(node.id()), projectId, index, node.kind(), node.title(), node.status(), node.year(), node.current());
        }
        for (LineageEdge edge : list(lineage.edges())) {
            jdbc.update("INSERT INTO lineage_edges(id, project_id, source_node_id, target_node_id, lineage_type, primary_lineage, rationale) VALUES(?,?,?,?,?,?,?)",
                    bytes(edge.id()), projectId, bytes(edge.sourceId()), bytes(edge.targetId()), edge.type().name(), edge.primary(), edge.rationale());
            if (studyExists(edge.sourceId()) && edge.targetId().equals(lineage.projectId())) {
                jdbc.update("INSERT INTO project_predecessors(project_id, study_id, lineage_type, primary_predecessor, rationale) VALUES(?,?,?,?,?)",
                        projectId, bytes(edge.sourceId()), edge.type().name(), edge.primary(), edge.rationale());
            }
        }
    }

    public Map<UUID, Lineage> lineages() {
        Map<UUID, Lineage> result = new LinkedHashMap<>();
        for (Project project : projects()) {
            byte[] projectId = bytes(project.id());
            List<LineageNode> nodes = jdbc.query("SELECT * FROM lineage_nodes WHERE project_id=? ORDER BY node_order",
                    (row, index) -> new LineageNode(uuid(row.getBytes("id")), row.getString("node_kind"), row.getString("title"),
                            row.getString("node_status"), row.getString("academic_year"), row.getBoolean("is_current")), projectId);
            if (nodes.isEmpty()) continue;
            List<LineageEdge> edges = jdbc.query("SELECT * FROM lineage_edges WHERE project_id=? ORDER BY id",
                    (row, index) -> new LineageEdge(uuid(row.getBytes("id")), uuid(row.getBytes("source_node_id")),
                            uuid(row.getBytes("target_node_id")), LineageType.valueOf(row.getString("lineage_type")),
                            row.getBoolean("primary_lineage"), row.getString("rationale")), projectId);
            result.put(project.id(), new Lineage(project.id(), nodes, edges));
        }
        return Map.copyOf(result);
    }

    @Transactional
    public void saveHealth(ProjectHealth health) {
        Project project = projects().stream().filter(candidate -> candidate.id().equals(health.projectId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Project does not exist: " + health.projectId()));
        UUID snapshotId = stableId("health:" + health.projectId() + ":" + health.calculatedAt());
        if (exists("health_snapshots", snapshotId)) return;
        Double alignment = dimensionScore(health, "alignment");
        Double readiness = dimensionScore(health, "requirement-readiness");
        Double verification = dimensionScore(health, "verification");
        Double scope = dimensionScore(health, "scope-stability");
        Double continuity = dimensionScore(health, "continuity-readiness");
        jdbc.update("INSERT INTO health_snapshots(id, project_id, baseline_id, alignment_score, requirement_readiness_score, verification_score, scope_stability_score, continuity_readiness_score, overall_status, calculation_json, calculated_at, open_findings, critical_findings, rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(snapshotId), bytes(health.projectId()), nullableBytes(project.currentBaselineId()), alignment, readiness, verification, scope,
                continuity, health.overallStatus(), "{}", timestamp(health.calculatedAt()), health.openFindings(), health.criticalFindings(), health.ruleVersion());
        for (int index = 0; index < list(health.dimensions()).size(); index++) {
            HealthDimension dimension = health.dimensions().get(index);
            jdbc.update("INSERT INTO health_dimensions(health_snapshot_id, dimension_order, dimension_key, label, assessment_status, dimension_score, health_band, explanation) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(snapshotId), index, dimension.key(), dimension.label(), dimension.status().name(), dimension.score(),
                    dimension.band(), dimension.explanation());
        }
    }

    public Map<UUID, ProjectHealth> health() {
        Map<UUID, ProjectHealth> result = new LinkedHashMap<>();
        for (Project project : projects()) {
            List<HealthRow> rows = jdbc.query("SELECT id, overall_status, calculated_at, open_findings, critical_findings, rule_version FROM health_snapshots WHERE project_id=? ORDER BY calculated_at DESC LIMIT 1",
                    (row, index) -> new HealthRow(row.getBytes("id"), row.getString("overall_status"), instant(row, "calculated_at"),
                            row.getInt("open_findings"), row.getInt("critical_findings"), row.getString("rule_version")), bytes(project.id()));
            if (rows.isEmpty()) continue;
            HealthRow latest = rows.getFirst();
            List<HealthDimension> dimensions = jdbc.query("SELECT * FROM health_dimensions WHERE health_snapshot_id=? ORDER BY dimension_order",
                    (row, index) -> new HealthDimension(row.getString("dimension_key"), row.getString("label"),
                            AssessmentStatus.valueOf(row.getString("assessment_status")), nullableDouble(row, "dimension_score"),
                            row.getString("health_band"), row.getString("explanation")), latest.id());
            result.put(project.id(), new ProjectHealth(project.id(), latest.overall(), dimensions, latest.open(), latest.critical(),
                    latest.calculatedAt(), latest.ruleVersion()));
        }
        return Map.copyOf(result);
    }

    @Transactional
    public void replaceReviewQueue(List<ReviewQueueItem> queue) {
        jdbc.update("DELETE FROM review_queue_items");
        for (ReviewQueueItem item : list(queue)) {
            jdbc.update("INSERT INTO review_queue_items(id, item_type, title, project_code, severity, required_role, reason_text, due_at) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(item.id()), item.type(), item.title(), item.projectCode(), item.severity().name(), item.requiredRole(),
                    item.reason(), timestamp(item.dueAt()));
        }
    }

    public List<ReviewQueueItem> reviewQueue() {
        return jdbc.query("SELECT * FROM review_queue_items ORDER BY due_at", (row, index) -> new ReviewQueueItem(
                uuid(row.getBytes("id")), row.getString("item_type"), row.getString("title"), row.getString("project_code"),
                Severity.valueOf(row.getString("severity")), row.getString("required_role"), row.getString("reason_text"), instant(row, "due_at")));
    }

    public WorkspaceState load() {
        List<Traceability> traceList = traceability();
        return new WorkspaceState(studies(), problems(), proposals(), proposalProblemIds(), discoveryRuns(), decisions(), projects(),
                projectProposalIds(), traceList, scopeRisks(), health(), changes(), impactPreviews(), completionPackages(), lineages(), reviewQueue());
    }

    public record WorkspaceState(
            List<Study> studies,
            List<ProblemCase> problems,
            List<Proposal> proposals,
            Map<UUID, UUID> proposalProblemIds,
            List<DiscoveryRun> discoveryRuns,
            List<ProposalDecision> decisions,
            List<Project> projects,
            Map<UUID, UUID> projectProposalIds,
            List<Traceability> traceability,
            Map<UUID, ScopeRisk> scopeRisks,
            Map<UUID, ProjectHealth> health,
            List<ChangeRequest> changeRequests,
            Map<UUID, ImpactPreview> impactPreviews,
            List<CompletionPackage> completionPackages,
            Map<UUID, Lineage> lineages,
            List<ReviewQueueItem> reviewQueue) {}

    private void saveCandidate(UUID runId, DiscoveryCandidate candidate) {
        UUID candidateId = stableId("candidate:" + runId + ":" + candidate.studyId());
        jdbc.update("INSERT INTO discovery_candidates(id, discovery_run_id, study_id, candidate_rank, problem_score, objective_score, solution_score, confidence_score, similarity_band, exact_match) VALUES(?,?,?,?,?,?,?,?,?,?)",
                bytes(candidateId), bytes(runId), bytes(candidate.studyId()), candidate.rank(), candidate.problemScore(),
                candidate.objectiveScore(), candidate.solutionScore(), candidate.confidence(), candidate.similarityBand(), candidate.exactMatch());
        for (int evidenceIndex = 0; evidenceIndex < list(candidate.evidence()).size(); evidenceIndex++) {
            CandidateEvidence evidence = candidate.evidence().get(evidenceIndex);
            UUID evidenceId = stableId("candidate-evidence:" + candidateId + ":" + evidenceIndex);
            double score = evidence.components().stream().mapToDouble(ComponentScore::weightedScore).sum();
            jdbc.update("INSERT INTO candidate_evidence(id, discovery_candidate_id, field_name, component_type, score, matched_excerpt, explanation, proposal_excerpt, study_excerpt) VALUES(?,?,?,?,?,?,?,?,?)",
                    bytes(evidenceId), bytes(candidateId), evidence.field(), "FIELD", score, evidence.studyExcerpt(),
                    "Explained hybrid field comparison.", evidence.proposalExcerpt(), evidence.studyExcerpt());
            for (int componentIndex = 0; componentIndex < list(evidence.components()).size(); componentIndex++) {
                ComponentScore component = evidence.components().get(componentIndex);
                UUID componentId = stableId("candidate-component:" + evidenceId + ":" + componentIndex);
                jdbc.update("INSERT INTO candidate_evidence_components(id, candidate_evidence_id, component_order, component_name, raw_score, score_weight, weighted_score, explanation) VALUES(?,?,?,?,?,?,?,?)",
                        bytes(componentId), bytes(evidenceId), componentIndex, component.component(), component.rawScore(), component.weight(),
                        component.weightedScore(), component.explanation());
                for (int termIndex = 0; termIndex < list(component.matchedTerms()).size(); termIndex++) {
                    jdbc.update("INSERT INTO candidate_component_terms(component_id, term_order, matched_term) VALUES(?,?,?)",
                            bytes(componentId), termIndex, component.matchedTerms().get(termIndex));
                }
            }
        }
    }

    private List<DiscoveryCandidate> discoveryCandidates(byte[] runId) {
        return jdbc.query("SELECT c.*, s.title AS study_title FROM discovery_candidates c JOIN studies s ON s.id=c.study_id WHERE c.discovery_run_id=? ORDER BY c.candidate_rank",
                (row, index) -> new DiscoveryCandidate(row.getInt("candidate_rank"), uuid(row.getBytes("study_id")),
                        row.getString("study_title"), row.getDouble("problem_score"), row.getDouble("objective_score"),
                        row.getDouble("solution_score"), row.getDouble("confidence_score"), row.getString("similarity_band"),
                        row.getBoolean("exact_match"), candidateEvidence(row.getBytes("id"))), runId);
    }

    private List<CandidateEvidence> candidateEvidence(byte[] candidateId) {
        return jdbc.query("SELECT * FROM candidate_evidence WHERE discovery_candidate_id=? ORDER BY id", (row, index) ->
                new CandidateEvidence(row.getString("field_name"), row.getString("proposal_excerpt"), row.getString("study_excerpt"),
                        componentScores(row.getBytes("id"))), candidateId);
    }

    private List<ComponentScore> componentScores(byte[] evidenceId) {
        return jdbc.query("SELECT * FROM candidate_evidence_components WHERE candidate_evidence_id=? ORDER BY component_order",
                (row, index) -> new ComponentScore(row.getString("component_name"), row.getDouble("raw_score"),
                        row.getDouble("score_weight"), row.getDouble("weighted_score"), row.getString("explanation"),
                        jdbc.query("SELECT matched_term FROM candidate_component_terms WHERE component_id=? ORDER BY term_order",
                                (term, termIndex) -> term.getString(1), row.getBytes("id"))), evidenceId);
    }

    private List<String> revisionChecklist(byte[] runId) {
        return jdbc.query("SELECT checklist_text FROM discovery_revision_checklist WHERE discovery_run_id=? ORDER BY checklist_order",
                (row, index) -> row.getString(1), runId);
    }

    private List<String> studyObjectives(byte[] studyId) {
        return jdbc.query("SELECT statement_text FROM study_objectives WHERE study_id=? ORDER BY objective_order",
                (row, index) -> row.getString(1), studyId);
    }

    private List<String> studyKeywords(byte[] studyId) {
        return jdbc.query("SELECT t.canonical_label FROM taxonomy_terms t JOIN study_terms s ON s.term_id=t.id WHERE s.study_id=? ORDER BY t.canonical_label",
                (row, index) -> row.getString(1), studyId);
    }

    private List<ContinuationItem> continuationItems(byte[] studyId) {
        return jdbc.query("SELECT c.*, CASE WHEN EXISTS(SELECT 1 FROM continuation_item_claims x WHERE x.continuation_item_id=c.id AND x.claim_status NOT IN ('REJECTED','WITHDRAWN')) THEN TRUE ELSE FALSE END AS claimed FROM continuation_items c WHERE c.study_id=? ORDER BY c.created_at, c.id",
                (row, index) -> new ContinuationItem(uuid(row.getBytes("id")), uuid(row.getBytes("study_id")),
                        row.getString("item_type"), row.getString("title"), row.getString("description"),
                        row.getString("item_status"), row.getBoolean("claimed")), studyId);
    }

    private List<String> proposalObjectives(byte[] proposalId) {
        return jdbc.query("SELECT statement_text FROM proposal_objectives WHERE proposal_id=? ORDER BY objective_order",
                (row, index) -> row.getString(1), proposalId);
    }

    private List<String> projectTeam(byte[] projectId) {
        return jdbc.query("SELECT display_name FROM project_team_members WHERE project_id=? ORDER BY member_order",
                (row, index) -> row.getString(1), projectId);
    }

    private void saveAnalysisSnapshot(Traceability trace) {
        Instant now = Instant.now();
        UUID runId = stableId("analysis:" + trace.projectId() + ":" + trace.baselineId() + ":" + sha256(trace.toString()));
        if (!exists("analysis_runs", runId)) {
            jdbc.update("INSERT INTO analysis_runs(id, project_id, baseline_id, analysis_type, assessment_status, rule_version, input_sha256, configuration_json, started_at, completed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    bytes(runId), bytes(trace.projectId()), bytes(trace.baselineId()), "ALIGNMENT", trace.assessmentStatus().name(),
                    "alignment-v1", sha256(trace.toString()), "{}", timestamp(now), timestamp(now));
        }
        for (Finding finding : list(trace.findings())) {
            if (exists("findings", finding.id())) {
                jdbc.update("UPDATE findings SET analysis_run_id=?, severity=?, finding_state=?, title=?, explanation=?, next_action=? WHERE id=? AND project_id=?",
                        bytes(runId), finding.severity().name(), finding.state().name(), finding.title(), finding.explanation(), finding.nextAction(),
                        bytes(finding.id()), bytes(trace.projectId()));
                jdbc.update("DELETE FROM finding_evidence WHERE finding_id=?", bytes(finding.id()));
            } else {
                jdbc.update("INSERT INTO findings(id, analysis_run_id, project_id, finding_code, severity, finding_state, title, explanation, next_action, accepted_by, acceptance_rationale, acceptance_expires_at, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        bytes(finding.id()), bytes(runId), bytes(trace.projectId()), finding.code(), finding.severity().name(),
                        finding.state().name(), finding.title(), finding.explanation(), finding.nextAction(), null, null, null, timestamp(now));
            }
            for (int index = 0; index < list(finding.implicatedItemIds()).size(); index++) {
                jdbc.update("INSERT INTO finding_evidence(id, finding_id, trace_item_id, trace_link_id, evidence_label, evidence_snapshot_json) VALUES(?,?,?,?,?,?)",
                        bytes(stableId("finding-evidence:" + finding.id() + ":" + index)), bytes(finding.id()),
                        bytes(finding.implicatedItemIds().get(index)), null, "Implicated trace item", null);
            }
        }
        UUID coverageId = stableId("coverage:" + runId);
        if (!exists("trace_coverage_snapshots", coverageId)) {
            Coverage coverage = trace.coverage();
            jdbc.update("INSERT INTO trace_coverage_snapshots(id, project_id, baseline_id, assessment_status, mapped_coverage, executed_coverage, passing_coverage, priority_weighted_passing_coverage, total_requirements, verified_requirements, calculated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(coverageId), bytes(trace.projectId()), bytes(trace.baselineId()), coverage.status().name(), coverage.mappedCoverage(),
                    coverage.executedCoverage(), coverage.passingCoverage(), coverage.priorityWeightedPassingCoverage(),
                    coverage.totalRequirements(), coverage.verifiedRequirements(), timestamp(now));
        }
    }

    private Optional<AssessmentStatus> latestTraceStatus(UUID projectId) {
        return jdbc.query("SELECT assessment_status FROM analysis_runs WHERE project_id=? AND analysis_type='ALIGNMENT' ORDER BY completed_at DESC LIMIT 1",
                (row, index) -> AssessmentStatus.valueOf(row.getString(1)), bytes(projectId)).stream().findFirst();
    }

    private List<Finding> latestFindings(UUID projectId) {
        List<byte[]> runs = jdbc.query("SELECT id FROM analysis_runs WHERE project_id=? AND analysis_type='ALIGNMENT' ORDER BY completed_at DESC LIMIT 1",
                (row, index) -> row.getBytes(1), bytes(projectId));
        if (runs.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM findings WHERE analysis_run_id=? ORDER BY severity DESC, finding_code", (row, index) ->
                new Finding(uuid(row.getBytes("id")), row.getString("finding_code"), Severity.valueOf(row.getString("severity")),
                        FindingState.valueOf(row.getString("finding_state")), row.getString("title"), row.getString("explanation"),
                        row.getString("next_action"), findingItems(row.getBytes("id")), "alignment-v1"), runs.getFirst());
    }

    private List<UUID> findingItems(byte[] findingId) {
        return jdbc.query("SELECT trace_item_id FROM finding_evidence WHERE finding_id=? AND trace_item_id IS NOT NULL ORDER BY id",
                (row, index) -> uuid(row.getBytes(1)), findingId);
    }

    private Coverage latestCoverage(UUID projectId, UUID baselineId) {
        List<Coverage> rows = jdbc.query("SELECT * FROM trace_coverage_snapshots WHERE project_id=? AND baseline_id=? ORDER BY calculated_at DESC LIMIT 1",
                (row, index) -> new Coverage(AssessmentStatus.valueOf(row.getString("assessment_status")), row.getDouble("mapped_coverage"),
                        row.getDouble("executed_coverage"), row.getDouble("passing_coverage"),
                        row.getDouble("priority_weighted_passing_coverage"), row.getInt("total_requirements"),
                        row.getInt("verified_requirements")), bytes(projectId), bytes(baselineId));
        return rows.isEmpty() ? new Coverage(AssessmentStatus.UNASSESSED, 0, 0, 0, 0, 0, 0) : rows.getFirst();
    }

    private UUID insertScopeRisk(UUID projectId, UUID baselineId, ScopeRisk risk, Instant calculatedAt) {
        UUID id = stableId("scope:" + projectId + ":" + calculatedAt + ":" + risk);
        if (exists("scope_risk_snapshots", id)) return id;
        jdbc.update("INSERT INTO scope_risk_snapshots(id, project_id, baseline_id, assessment_status, risk_score, risk_band, governance_score, alignment_score, controlled_growth_score, boundary_score, calculated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                bytes(id), bytes(projectId), nullableBytes(baselineId), risk.status().name(), risk.score(), risk.band(), risk.governance(),
                risk.alignment(), risk.controlledGrowth(), risk.boundary(), timestamp(calculatedAt));
        for (int index = 0; index < list(risk.explanations()).size(); index++) {
            jdbc.update("INSERT INTO scope_risk_explanations(scope_risk_snapshot_id, explanation_order, explanation_text) VALUES(?,?,?)",
                    bytes(id), index, risk.explanations().get(index));
        }
        return id;
    }

    private Optional<ScopeRisk> latestScopeRisk(UUID projectId) {
        return jdbc.query("SELECT id FROM scope_risk_snapshots WHERE project_id=? ORDER BY calculated_at DESC LIMIT 1",
                (row, index) -> scopeRiskById(uuid(row.getBytes(1))), bytes(projectId)).stream().findFirst();
    }

    private ScopeRisk scopeRiskById(UUID id) {
        return jdbc.queryForObject("SELECT * FROM scope_risk_snapshots WHERE id=?", (row, index) -> new ScopeRisk(
                AssessmentStatus.valueOf(row.getString("assessment_status")), nullableInteger(row, "risk_score"), row.getString("risk_band"),
                row.getInt("governance_score"), row.getInt("alignment_score"), row.getInt("controlled_growth_score"),
                row.getInt("boundary_score"), jdbc.query("SELECT explanation_text FROM scope_risk_explanations WHERE scope_risk_snapshot_id=? ORDER BY explanation_order",
                        (child, childIndex) -> child.getString(1), bytes(id))), bytes(id));
    }

    private List<ImpactedArtifact> impactArtifacts(UUID changeId) {
        return jdbc.query("SELECT p.*, i.item_key, i.item_type, r.title FROM impact_paths p JOIN trace_items i ON i.id=p.impacted_item_id JOIN trace_item_revisions r ON r.trace_item_id=i.id AND r.revision_number=i.current_revision WHERE p.change_request_id=? ORDER BY p.hop_count, i.item_key",
                (row, index) -> new ImpactedArtifact(uuid(row.getBytes("impacted_item_id")), row.getString("item_key"),
                        TraceItemType.valueOf(row.getString("item_type")), row.getString("title"), row.getInt("hop_count"),
                        jdbc.query("SELECT trace_item_id FROM impact_path_nodes WHERE impact_path_id=? ORDER BY node_order",
                                (node, nodeIndex) -> uuid(node.getBytes(1)), row.getBytes("id")), Severity.valueOf(row.getString("severity")),
                        row.getBoolean("evidence_becomes_stale"), row.getString("reason_text")), bytes(changeId));
    }

    private void saveCompletionItems(byte[] packageId, String type, List<String> values) {
        for (int index = 0; index < list(values).size(); index++) {
            String text = values.get(index);
            jdbc.update("INSERT INTO completion_package_items(id, completion_package_id, item_type, title, item_status, document_id, notes) VALUES(?,?,?,?,?,?,?)",
                    bytes(stableId("completion-item:" + uuid(packageId) + ":" + type + ":" + index)), packageId, type,
                    abbreviate(text, 300), "RECORDED", null, text);
        }
    }

    private List<String> completionItems(byte[] packageId, String type) {
        return jdbc.query("SELECT notes, title FROM completion_package_items WHERE completion_package_id=? AND item_type=? ORDER BY id",
                (row, index) -> Optional.ofNullable(row.getString("notes")).orElse(row.getString("title")), packageId, type);
    }

    private RepositoryRow repository(UUID projectId) {
        return jdbc.query("SELECT repository_url, commit_hash, setup_instructions FROM repository_references WHERE project_id=? ORDER BY id LIMIT 1",
                (row, index) -> new RepositoryRow(row.getString(1), row.getString(2), row.getString(3)), bytes(projectId))
                .stream().findFirst().orElse(new RepositoryRow("", "", ""));
    }

    private static Double dimensionScore(ProjectHealth health, String key) {
        return health.dimensions().stream().filter(dimension -> key.equals(dimension.key())).map(HealthDimension::score)
                .findFirst().orElse(null);
    }

    private byte[] departmentId(String departmentName) {
        if (departmentName != null && !departmentName.isBlank()) {
            List<byte[]> matches = jdbc.query("SELECT id FROM departments WHERE name=?", (row, index) -> row.getBytes(1), departmentName);
            if (!matches.isEmpty()) return matches.getFirst();
        }
        return defaultDepartmentId();
    }

    private byte[] optionalDepartmentId(String departmentName) {
        if (departmentName == null || departmentName.isBlank()) return null;
        List<byte[]> matches = jdbc.query(
                "SELECT id FROM departments WHERE LOWER(name)=LOWER(?) OR LOWER(code)=LOWER(?) ORDER BY code",
                (row, index) -> row.getBytes(1), departmentName.strip(), departmentName.strip());
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private byte[] defaultDepartmentId() {
        return jdbc.query("SELECT id FROM departments WHERE active=TRUE ORDER BY code LIMIT 1", (row, index) -> row.getBytes(1))
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Identity bootstrap must create a department before workspace persistence."));
    }

    private byte[] defaultActorId() {
        return jdbc.query("SELECT id FROM user_accounts WHERE account_status='ACTIVE' ORDER BY created_at LIMIT 1", (row, index) -> row.getBytes(1))
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Identity bootstrap must create an active account before workspace persistence."));
    }

    private boolean baselineExists(UUID id) {
        return id != null && exists("project_baselines", id);
    }

    private boolean studyExists(UUID id) {
        return id != null && exists("studies", id);
    }

    private boolean exists(String table, UUID id) {
        if (id == null) return false;
        return count("SELECT COUNT(*) FROM " + table + " WHERE id=?", bytes(id)) > 0;
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private static String dispositionRelationship(DecisionDisposition disposition) {
        return switch (disposition) {
            case APPROVE_CONTINUE -> "CONTINUES";
            case APPROVE_IMPROVE -> "IMPROVES";
            default -> "REFERENCES";
        };
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static byte[] nullableBytes(UUID id) {
        return id == null ? null : bytes(id);
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer value = ByteBuffer.wrap(bytes);
        return new UUID(value.getLong(), value.getLong());
    }

    private static UUID nullableUuid(byte[] bytes) {
        return bytes == null ? null : uuid(bytes);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(("ugnay:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instantOr(ResultSet result, String preferred, String fallback) throws SQLException {
        Instant value = instant(result, preferred);
        return value == null ? instant(result, fallback) : value;
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private record RepositoryRow(String url, String commit, String setup) {}
    private record HealthRow(byte[] id, String overall, Instant calculatedAt, int open, int critical, String ruleVersion) {}
}

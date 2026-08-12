package com.ugnay.platform.warehouse;

import com.ugnay.platform.warehouse.WarehouseContracts.AnalyticsFilters;
import com.ugnay.platform.warehouse.WarehouseContracts.AnalyticsView;
import com.ugnay.platform.warehouse.WarehouseContracts.ContinuationHistoryItem;
import com.ugnay.platform.warehouse.WarehouseContracts.ContinuationHistoryView;
import com.ugnay.platform.warehouse.WarehouseContracts.DepartmentCount;
import com.ugnay.platform.warehouse.WarehouseContracts.LoadView;
import com.ugnay.platform.warehouse.WarehouseContracts.QualityIssueView;
import com.ugnay.platform.warehouse.WarehouseContracts.QualitySummary;
import com.ugnay.platform.warehouse.WarehouseContracts.StageView;
import com.ugnay.platform.warehouse.WarehouseContracts.TopicCount;
import com.ugnay.platform.warehouse.WarehouseContracts.TopicTrend;
import com.ugnay.platform.warehouse.WarehouseContracts.YearCount;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

@Repository
public class WarehouseRepository {
    static final List<String> STAGES = List.of("COLLECT", "VALIDATE", "CLEAN", "TRANSFORM", "STORE", "ANALYZE");
    private static final Set<String> SAFE_VISIBILITIES = Set.of("PUBLIC", "CAMPUS", "INTERNAL", "RESTRICTED", "EMBARGOED");
    private static final Set<String> ANALYTIC_TERM_TYPES = Set.of("TOPIC", "KEYWORD", "RESEARCH_AREA");

    private final JdbcTemplate jdbc;

    public WarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID createLoad(String actorEmail) {
        byte[] actor = jdbc.query("SELECT id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                        (row, index) -> row.getBytes(1), actorEmail)
                .stream().findFirst().orElseThrow(() -> new AccessDeniedException("The authenticated account is not active."));
        UUID loadId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO warehouse_loads(id,requested_by,load_status,current_stage,source_cutoff_at,source_sha256,source_count,accepted_count,rejected_count,published_snapshot_id,failure_reason,started_at,completed_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(loadId), actor, "RUNNING", "COLLECT", Timestamp.from(now), null, 0, 0, 0, null, null,
                Timestamp.from(now), null);
        for (int index = 0; index < STAGES.size(); index++) {
            jdbc.update("INSERT INTO warehouse_load_stages(load_id,stage_code,stage_order,stage_status,input_count,output_count,details_json,started_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    bytes(loadId), STAGES.get(index), index + 1, "PENDING", 0, 0, "{}", null, null);
        }
        return loadId;
    }

    @Transactional
    public void beginStage(UUID loadId, String stage) {
        requireStage(stage);
        int changed = jdbc.update("UPDATE warehouse_load_stages SET stage_status='RUNNING',started_at=?,completed_at=NULL WHERE load_id=? AND stage_code=? AND stage_status='PENDING'",
                Timestamp.from(Instant.now()), bytes(loadId), stage);
        if (changed != 1) throw new IllegalStateException("Warehouse stage " + stage + " is not pending.");
        jdbc.update("UPDATE warehouse_loads SET current_stage=? WHERE id=? AND load_status='RUNNING'", stage, bytes(loadId));
    }

    @Transactional
    public void completeStage(UUID loadId, String stage, int inputCount, int outputCount, String detailsJson) {
        requireStage(stage);
        int changed = jdbc.update("UPDATE warehouse_load_stages SET stage_status='COMPLETED',input_count=?,output_count=?,details_json=?,completed_at=? "
                        + "WHERE load_id=? AND stage_code=? AND stage_status='RUNNING'",
                inputCount, outputCount, detailsJson, Timestamp.from(Instant.now()), bytes(loadId), stage);
        if (changed != 1) throw new IllegalStateException("Warehouse stage " + stage + " did not complete from RUNNING.");
    }

    @Transactional
    public void coalesce(UUID loadId, UUID snapshotId, int sourceCount) {
        int accepted = jdbc.queryForObject("SELECT accepted_study_count FROM warehouse_snapshots WHERE id=? AND snapshot_status='PUBLISHED'",
                Integer.class, bytes(snapshotId));
        int rejected = jdbc.queryForObject("SELECT rejected_study_count FROM warehouse_snapshots WHERE id=? AND snapshot_status='PUBLISHED'",
                Integer.class, bytes(snapshotId));
        Instant now = Instant.now();
        for (String stage : STAGES.subList(1, STAGES.size())) {
            jdbc.update("UPDATE warehouse_load_stages SET stage_status='SKIPPED',input_count=?,output_count=?,details_json=?,started_at=?,completed_at=? "
                            + "WHERE load_id=? AND stage_code=? AND stage_status='PENDING'",
                    sourceCount, sourceCount, "{\"reason\":\"UNCHANGED_SOURCE\"}", Timestamp.from(now), Timestamp.from(now),
                    bytes(loadId), stage);
        }
        jdbc.update("UPDATE warehouse_loads SET load_status='UNCHANGED',current_stage='ANALYZE',published_snapshot_id=?,accepted_count=?,rejected_count=?,completed_at=? WHERE id=?",
                bytes(snapshotId), accepted, rejected, Timestamp.from(now), bytes(loadId));
    }

    @Transactional
    public void failLoad(UUID loadId, String failureReason) {
        String safeReason = abbreviate(failureReason == null ? "Warehouse refresh failed." : failureReason, 1000);
        Instant now = Instant.now();
        jdbc.update("UPDATE warehouse_load_stages SET stage_status='FAILED',details_json=?,completed_at=? WHERE load_id=? AND stage_status='RUNNING'",
                "{\"result\":\"FAILED\"}", Timestamp.from(now), bytes(loadId));
        jdbc.update("UPDATE warehouse_loads SET load_status='FAILED',failure_reason=?,completed_at=? WHERE id=?",
                safeReason, Timestamp.from(now), bytes(loadId));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Collected collect(UUID loadId, int maxStudies, int maxSourceRows) {
        jdbc.update("UPDATE warehouse_loads SET source_cutoff_at=? WHERE id=? AND load_status='RUNNING'",
                Timestamp.from(Instant.now()), bytes(loadId));
        int studyCount = count("SELECT COUNT(*) FROM studies");
        if (studyCount > maxStudies) {
            throw new IllegalStateException("The catalogue has " + studyCount + " studies; the configured warehouse limit is " + maxStudies + ".");
        }
        int sourceRows = studyCount
                + count("SELECT COUNT(*) FROM study_objectives")
                + count("SELECT COUNT(*) FROM study_metadata_versions")
                + count("SELECT COUNT(*) FROM study_terms")
                + count("SELECT COUNT(*) FROM discovery_candidates")
                + count("SELECT COUNT(*) FROM study_relationships")
                + count("SELECT COUNT(*) FROM project_predecessors")
                + count("SELECT COUNT(*) FROM continuation_item_claims")
                + count("SELECT COUNT(*) FROM continuation_claim_outcomes")
                + count("SELECT COUNT(*) FROM continuation_claim_events")
                + count("SELECT COUNT(*) FROM studies WHERE source_project_id IS NOT NULL");
        if (sourceRows > maxSourceRows) {
            throw new IllegalStateException("The warehouse source has " + sourceRows + " rows; the configured limit is " + maxSourceRows + ".");
        }

        List<StagedStudy> studies = jdbc.query("SELECT s.id,s.department_id,d.code AS department_code,d.name AS department_name,s.institutional_code,s.doi,s.repository_identifier,s.program_name,"
                        + "s.title,s.abstract_text,s.problem_statement,s.methodology,s.features_text,s.data_sources_text,s.technology_text,s.intended_users_text,s.stakeholders_text,s.site_context,s.keywords_text,s.results_text,"
                        + "s.academic_year,s.completion_year,s.lifecycle_status,s.visibility,s.published_at,s.archived_at,s.row_version,s.created_at "
                        + "FROM studies s LEFT JOIN departments d ON d.id=s.department_id ORDER BY s.id",
                (row, index) -> stagedStudy(row));
        for (StagedStudy study : studies) insertStagedStudy(loadId, study);

        List<ObjectiveSource> objectives = jdbc.query("SELECT so.id,so.study_id,so.objective_order,so.statement_text FROM study_objectives so ORDER BY so.study_id,so.objective_order,so.id",
                (row, index) -> new ObjectiveSource(row.getBytes("id"), row.getBytes("study_id"), row.getInt("objective_order"), row.getString("statement_text")));
        for (ObjectiveSource row : objectives) {
            jdbc.update("INSERT INTO warehouse_staged_objectives(load_id,study_id,objective_id,objective_order,statement_text,normalized_statement) VALUES(?,?,?,?,?,?)",
                    bytes(loadId), row.studyId(), row.id(), row.order(), row.statement(), null);
        }
        List<MetadataVersionSource> versions = jdbc.query("SELECT id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_at FROM study_metadata_versions ORDER BY study_id,version_number,id",
                (row, index) -> new MetadataVersionSource(row.getBytes("id"), row.getBytes("study_id"), row.getInt("version_number"),
                        row.getString("provenance_type"), row.getString("source_sha256"), row.getString("metadata_json"), row.getTimestamp("recorded_at")));
        for (MetadataVersionSource row : versions) {
            jdbc.update("INSERT INTO warehouse_staged_metadata_versions(load_id,metadata_version_id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                    bytes(loadId), row.id(), row.studyId(), row.version(), row.provenance(), row.sourceHash(), row.metadataJson(), row.recordedAt());
        }
        List<TopicSource> topics = jdbc.query("SELECT st.study_id,t.id,t.term_type,t.canonical_label,t.active FROM study_terms st JOIN taxonomy_terms t ON t.id=st.term_id ORDER BY st.study_id,t.id",
                (row, index) -> new TopicSource(row.getBytes("study_id"), row.getBytes("id"), row.getString("term_type"),
                        row.getString("canonical_label"), row.getBoolean("active")));
        for (TopicSource row : topics) {
            jdbc.update("INSERT INTO warehouse_staged_topics(load_id,study_id,term_id,term_type,canonical_label,normalized_label,active) VALUES(?,?,?,?,?,?,?)",
                    bytes(loadId), row.studyId(), row.id(), row.type(), row.label(), null, row.active());
        }
        List<RetrievalSource> retrievals = jdbc.query("SELECT dc.discovery_run_id,dc.study_id,dc.candidate_rank,dc.problem_score,dc.objective_score,dc.solution_score,dc.confidence_score,dc.similarity_band,dc.exact_match,"
                        + "dr.assessment_status,ac.algorithm_version,dr.started_at,dr.completed_at FROM discovery_candidates dc "
                        + "JOIN discovery_runs dr ON dr.id=dc.discovery_run_id JOIN algorithm_configurations ac ON ac.id=dr.algorithm_configuration_id "
                        + "ORDER BY dc.discovery_run_id,dc.candidate_rank",
                (row, index) -> new RetrievalSource(row.getBytes("discovery_run_id"), row.getBytes("study_id"), row.getInt("candidate_rank"),
                        row.getBigDecimal("problem_score"), row.getBigDecimal("objective_score"), row.getBigDecimal("solution_score"),
                        row.getBigDecimal("confidence_score"), row.getString("similarity_band"), row.getBoolean("exact_match"),
                        row.getString("assessment_status"), row.getString("algorithm_version"), row.getTimestamp("started_at"),
                        row.getTimestamp("completed_at")));
        for (RetrievalSource row : retrievals) {
            jdbc.update("INSERT INTO warehouse_staged_retrievals(load_id,discovery_run_id,study_id,candidate_rank,problem_score,objective_score,solution_score,confidence_score,similarity_band,exact_match,run_status,algorithm_version,run_started_at,run_completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(loadId), row.runId(), row.studyId(), row.rank(), row.problem(), row.objective(), row.solution(), row.confidence(),
                    row.band(), row.exact(), row.status(), row.algorithm(), row.startedAt(), row.completedAt());
        }

        collectContinuity(loadId);
        String sourceHash = hashStaged(loadId);
        jdbc.update("UPDATE warehouse_loads SET source_sha256=?,source_count=? WHERE id=?",
                sourceHash, studyCount, bytes(loadId));
        return new Collected(studyCount, sourceRows, sourceHash);
    }

    @Transactional
    public ValidationResult validate(UUID loadId) {
        jdbc.update("DELETE FROM warehouse_quality_issues WHERE load_id=?", bytes(loadId));
        List<ValidationStudy> studies = jdbc.query("SELECT study_id,title,academic_year,source_completion_year,lifecycle_status,visibility,department_id,institutional_code FROM warehouse_staged_studies WHERE load_id=? ORDER BY study_id",
                (row, index) -> new ValidationStudy(uuid(row.getBytes("study_id")), row.getString("title"), row.getString("academic_year"),
                        nullableInteger(row, "source_completion_year"), row.getString("lifecycle_status"), row.getString("visibility"),
                        row.getBytes("department_id"), row.getString("institutional_code")),
                bytes(loadId));
        int rejected = 0;
        for (ValidationStudy study : studies) {
            boolean valid = true;
            OptionalInt year = WarehouseYearParser.completionYear(study.academicYear());
            if (WarehouseYearParser.isMissing(study.academicYear())) {
                issue(loadId, study.id(), "ACADEMIC_YEAR_UNAVAILABLE", "WARNING", "academicYear",
                        "Academic year is unavailable; year-based analysis excludes this study.");
            } else if (year.isEmpty()) {
                issue(loadId, study.id(), "ACADEMIC_YEAR_INVALID", "WARNING", "academicYear",
                        "Academic year must be YYYY or a consecutive YYYY-YYYY range; year-based analysis excludes this study.");
            }
            if (study.sourceCompletionYear() != null && (study.sourceCompletionYear() < 1900 || study.sourceCompletionYear() > 2200)) {
                issue(loadId, study.id(), "COMPLETION_YEAR_INVALID", "WARNING", "completionYear",
                        "The stored completion year is outside the accepted range and is excluded from analysis.");
            } else if (study.sourceCompletionYear() != null && year.isPresent() && study.sourceCompletionYear() != year.getAsInt()) {
                issue(loadId, study.id(), "COMPLETION_YEAR_MISMATCH", "WARNING", "completionYear",
                        "The stored completion year disagrees with the strict academic-year derivation; the academic-year derivation is used.");
            }
            if (study.title() == null || study.title().isBlank()) {
                valid = false;
                issue(loadId, study.id(), "TITLE_MISSING", "ERROR", "title", "A warehouse study requires its authoritative title.");
            }
            if (study.lifecycleStatus() == null || study.lifecycleStatus().isBlank()) {
                valid = false;
                issue(loadId, study.id(), "LIFECYCLE_STATUS_MISSING", "ERROR", "lifecycleStatus", "Lifecycle status is required for safe analysis.");
            }
            if (!SAFE_VISIBILITIES.contains(upper(study.visibility()))) {
                valid = false;
                issue(loadId, study.id(), "VISIBILITY_UNSUPPORTED", "ERROR", "visibility",
                        "Unsupported visibility prevents this study from entering a published snapshot.");
            }
            if (study.departmentId() == null) {
                issue(loadId, study.id(), "DEPARTMENT_UNAVAILABLE", "WARNING", "department",
                        "Department is unavailable; department analysis reports this study as unavailable.");
            }
            if (study.institutionalCode() == null || study.institutionalCode().isBlank()) {
                issue(loadId, study.id(), "INSTITUTIONAL_CODE_UNAVAILABLE", "INFO", "institutionalCode",
                        "Institutional code is unavailable; no value was inferred.");
            }
            jdbc.update("UPDATE warehouse_staged_studies SET completion_year=?,valid_record=? WHERE load_id=? AND study_id=?",
                    year.isPresent() ? year.getAsInt() : null, valid, bytes(loadId), bytes(study.id()));
            if (!valid) rejected++;
        }
        validateDuplicateIdentifier(loadId, "repository_identifier", "DUPLICATE_REPOSITORY_IDENTIFIER", "repositoryIdentifier");
        validateTopicTypes(loadId);
        int accepted = studies.size() - rejected;
        jdbc.update("UPDATE warehouse_loads SET accepted_count=?,rejected_count=? WHERE id=?", accepted, rejected, bytes(loadId));
        int issues = count("SELECT COUNT(*) FROM warehouse_quality_issues WHERE load_id=?", bytes(loadId));
        return new ValidationResult(studies.size(), accepted, rejected, issues);
    }

    @Transactional
    public int clean(UUID loadId) {
        List<StudyText> studies = jdbc.query("SELECT study_id,title FROM warehouse_staged_studies WHERE load_id=? ORDER BY study_id",
                (row, index) -> new StudyText(row.getBytes("study_id"), row.getString("title")), bytes(loadId));
        for (StudyText study : studies) {
            jdbc.update("UPDATE warehouse_staged_studies SET normalized_title=? WHERE load_id=? AND study_id=?",
                    normalize(study.text(), false), bytes(loadId), study.id());
        }
        List<StudyChildText> objectives = jdbc.query("SELECT study_id,objective_id,statement_text FROM warehouse_staged_objectives WHERE load_id=? ORDER BY study_id,objective_order",
                (row, index) -> new StudyChildText(row.getBytes("study_id"), row.getBytes("objective_id"), row.getString("statement_text")), bytes(loadId));
        for (StudyChildText row : objectives) {
            jdbc.update("UPDATE warehouse_staged_objectives SET normalized_statement=? WHERE load_id=? AND study_id=? AND objective_id=?",
                    normalize(row.text(), false), bytes(loadId), row.studyId(), row.childId());
        }
        List<StudyChildText> topics = jdbc.query("SELECT study_id,term_id,canonical_label FROM warehouse_staged_topics WHERE load_id=? ORDER BY study_id,term_id",
                (row, index) -> new StudyChildText(row.getBytes("study_id"), row.getBytes("term_id"), row.getString("canonical_label")), bytes(loadId));
        for (StudyChildText row : topics) {
            jdbc.update("UPDATE warehouse_staged_topics SET normalized_label=? WHERE load_id=? AND study_id=? AND term_id=?",
                    normalize(row.text(), true), bytes(loadId), row.studyId(), row.childId());
        }
        return studies.size();
    }

    @Transactional
    public int transform(UUID loadId) {
        jdbc.update("UPDATE warehouse_staged_studies s SET objective_count=(SELECT COUNT(*) FROM warehouse_staged_objectives o WHERE o.load_id=s.load_id AND o.study_id=s.study_id),"
                        + "topic_count=(SELECT COUNT(*) FROM warehouse_staged_topics t WHERE t.load_id=s.load_id AND t.study_id=s.study_id AND t.active=TRUE),"
                        + "continuation_count=(SELECT COUNT(*) FROM warehouse_staged_continuity c WHERE c.load_id=s.load_id AND c.source_study_id=s.study_id),"
                        + "retrieval_count=(SELECT COUNT(*) FROM warehouse_staged_retrievals r WHERE r.load_id=s.load_id AND r.study_id=s.study_id) WHERE s.load_id=?",
                bytes(loadId));
        List<byte[]> ids = jdbc.query("SELECT study_id FROM warehouse_staged_studies WHERE load_id=? ORDER BY study_id",
                (row, index) -> row.getBytes(1), bytes(loadId));
        for (byte[] studyId : ids) {
            String rowHash = hashStudy(loadId, studyId);
            jdbc.update("UPDATE warehouse_staged_studies SET transformed_sha256=? WHERE load_id=? AND study_id=?",
                    rowHash, bytes(loadId), studyId);
        }
        return ids.size();
    }

    @Transactional
    public UUID store(UUID loadId) {
        LoadSource load = loadSource(loadId);
        UUID snapshotId = UUID.randomUUID();
        int version = count("SELECT COALESCE(MAX(snapshot_version),0) FROM warehouse_snapshots") + 1;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO warehouse_snapshots(id,load_id,snapshot_version,snapshot_status,source_sha256,source_cutoff_at,source_study_count,accepted_study_count,rejected_study_count,created_at,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                bytes(snapshotId), bytes(loadId), version, "BUILDING", load.sourceHash(), Timestamp.from(load.cutoff()),
                load.sourceCount(), load.acceptedCount(), load.rejectedCount(), Timestamp.from(now), null);

        jdbc.update("INSERT INTO dw_department_dimensions(snapshot_id,department_id,department_code,department_name) "
                        + "SELECT ?,department_id,department_code,department_name FROM warehouse_staged_studies WHERE load_id=? AND valid_record=TRUE AND department_id IS NOT NULL "
                        + "GROUP BY department_id,department_code,department_name",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_year_dimensions(snapshot_id,completion_year) SELECT ?,completion_year FROM warehouse_staged_studies "
                        + "WHERE load_id=? AND valid_record=TRUE AND completion_year IS NOT NULL GROUP BY completion_year",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_topic_dimensions(snapshot_id,term_id,term_type,canonical_label,normalized_label,active) "
                        + "SELECT ?,t.term_id,t.term_type,t.canonical_label,t.normalized_label,t.active FROM warehouse_staged_topics t "
                        + "JOIN warehouse_staged_studies s ON s.load_id=t.load_id AND s.study_id=t.study_id "
                        + "WHERE t.load_id=? AND s.valid_record=TRUE AND t.active=TRUE GROUP BY t.term_id,t.term_type,t.canonical_label,t.normalized_label,t.active",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_study_dimensions(snapshot_id,study_id,department_id,institutional_code,doi,repository_identifier,program_name,title,normalized_title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,results_text,academic_year,source_completion_year,completion_year,lifecycle_status,visibility,published_at,archived_at,source_row_version,source_created_at,snapshot_row_sha256) "
                        + "SELECT ?,study_id,department_id,institutional_code,doi,repository_identifier,program_name,title,normalized_title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,results_text,academic_year,source_completion_year,completion_year,lifecycle_status,visibility,published_at,archived_at,source_row_version,source_created_at,transformed_sha256 "
                        + "FROM warehouse_staged_studies WHERE load_id=? AND valid_record=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_study_objective_facts(snapshot_id,study_id,objective_id,objective_order,statement_text,normalized_statement) "
                        + "SELECT ?,o.study_id,o.objective_id,o.objective_order,o.statement_text,o.normalized_statement FROM warehouse_staged_objectives o "
                        + "JOIN warehouse_staged_studies s ON s.load_id=o.load_id AND s.study_id=o.study_id WHERE o.load_id=? AND s.valid_record=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_study_version_dimensions(snapshot_id,metadata_version_id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_at) "
                        + "SELECT ?,v.metadata_version_id,v.study_id,v.version_number,v.provenance_type,v.source_sha256,v.metadata_json,v.recorded_at FROM warehouse_staged_metadata_versions v "
                        + "JOIN warehouse_staged_studies s ON s.load_id=v.load_id AND s.study_id=v.study_id WHERE v.load_id=? AND s.valid_record=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_study_topic_bridge(snapshot_id,study_id,term_id) SELECT ?,t.study_id,t.term_id FROM warehouse_staged_topics t "
                        + "JOIN warehouse_staged_studies s ON s.load_id=t.load_id AND s.study_id=t.study_id WHERE t.load_id=? AND s.valid_record=TRUE AND t.active=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_study_facts(snapshot_id,study_id,objective_count,topic_count,continuation_count,retrieval_count) "
                        + "SELECT ?,study_id,objective_count,topic_count,continuation_count,retrieval_count FROM warehouse_staged_studies WHERE load_id=? AND valid_record=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_retrieval_facts(snapshot_id,discovery_run_id,study_id,candidate_rank,problem_score,objective_score,solution_score,confidence_score,similarity_band,exact_match,run_status,algorithm_version,run_started_at,run_completed_at) "
                        + "SELECT ?,r.discovery_run_id,r.study_id,r.candidate_rank,r.problem_score,r.objective_score,r.solution_score,r.confidence_score,r.similarity_band,r.exact_match,r.run_status,r.algorithm_version,r.run_started_at,r.run_completed_at "
                        + "FROM warehouse_staged_retrievals r JOIN warehouse_staged_studies s ON s.load_id=r.load_id AND s.study_id=r.study_id WHERE r.load_id=? AND s.valid_record=TRUE",
                bytes(snapshotId), bytes(loadId));
        jdbc.update("INSERT INTO dw_continuation_facts(snapshot_id,fact_key,source_kind,source_study_id,target_study_id,successor_project_id,continuation_item_id,relationship_type,evidence_status,rationale_text,evidence_at) "
                        + "SELECT ?,c.fact_key,c.source_kind,c.source_study_id,c.target_study_id,c.successor_project_id,c.continuation_item_id,c.relationship_type,c.evidence_status,c.rationale_text,c.evidence_at "
                        + "FROM warehouse_staged_continuity c WHERE c.load_id=? "
                        + "AND (c.source_study_id IS NULL OR EXISTS(SELECT 1 FROM warehouse_staged_studies ss WHERE ss.load_id=c.load_id AND ss.study_id=c.source_study_id AND ss.valid_record=TRUE)) "
                        + "AND (c.target_study_id IS NULL OR EXISTS(SELECT 1 FROM warehouse_staged_studies ts WHERE ts.load_id=c.load_id AND ts.study_id=c.target_study_id AND ts.valid_record=TRUE))",
                bytes(snapshotId), bytes(loadId));
        return snapshotId;
    }

    @Transactional
    public SnapshotResult analyzeAndPublish(UUID loadId, UUID snapshotId) {
        int expected = jdbc.queryForObject("SELECT accepted_study_count FROM warehouse_snapshots WHERE id=? AND snapshot_status='BUILDING'",
                Integer.class, bytes(snapshotId));
        int actual = count("SELECT COUNT(*) FROM dw_study_facts WHERE snapshot_id=?", bytes(snapshotId));
        if (expected != actual) throw new IllegalStateException("Warehouse fact count did not match validated studies.");
        int yearRows = count("SELECT COUNT(*) FROM dw_year_dimensions WHERE snapshot_id=?", bytes(snapshotId));
        int departmentRows = count("SELECT COUNT(*) FROM dw_department_dimensions WHERE snapshot_id=?", bytes(snapshotId));
        int topicRows = count("SELECT COUNT(*) FROM dw_topic_dimensions WHERE snapshot_id=?", bytes(snapshotId));
        int continuationRows = count("SELECT COUNT(*) FROM dw_continuation_facts WHERE snapshot_id=?", bytes(snapshotId));
        Instant now = Instant.now();
        jdbc.update("UPDATE warehouse_snapshots SET snapshot_status='PUBLISHED',published_at=? WHERE id=? AND snapshot_status='BUILDING'",
                Timestamp.from(now), bytes(snapshotId));
        jdbc.update("UPDATE warehouse_load_stages SET stage_status='COMPLETED',input_count=?,output_count=?,details_json=?,completed_at=? "
                        + "WHERE load_id=? AND stage_code='ANALYZE' AND stage_status='RUNNING'",
                actual, actual, "{\"result\":\"PUBLISHED\"}", Timestamp.from(now), bytes(loadId));
        jdbc.update("UPDATE warehouse_loads SET load_status='PUBLISHED',current_stage='ANALYZE',published_snapshot_id=?,completed_at=? WHERE id=?",
                bytes(snapshotId), Timestamp.from(now), bytes(loadId));
        return new SnapshotResult(actual, yearRows, departmentRows, topicRows, continuationRows);
    }

    public Optional<UUID> publishedSnapshotByHash(String sourceHash) {
        return jdbc.query("SELECT id FROM warehouse_snapshots WHERE source_sha256=? AND snapshot_status='PUBLISHED' ORDER BY published_at DESC",
                (row, index) -> uuid(row.getBytes(1)), sourceHash).stream().findFirst();
    }

    public LoadView load(UUID loadId) {
        List<LoadView> rows = jdbc.query("SELECT id,load_status,current_stage,source_sha256,source_count,accepted_count,rejected_count,published_snapshot_id,source_cutoff_at,started_at,completed_at,failure_reason FROM warehouse_loads WHERE id=?",
                (row, index) -> mapLoad(row), bytes(loadId));
        if (rows.isEmpty()) throw new NoSuchElementException("Warehouse load does not exist: " + loadId);
        return rows.getFirst();
    }

    public LoadView latestLoad() {
        return jdbc.query("SELECT id,load_status,current_stage,source_sha256,source_count,accepted_count,rejected_count,published_snapshot_id,source_cutoff_at,started_at,completed_at,failure_reason FROM warehouse_loads ORDER BY started_at DESC,id DESC LIMIT 1",
                (row, index) -> mapLoad(row)).stream().findFirst().orElseGet(LoadView::unassessed);
    }

    public List<QualityIssueView> qualityIssues(UUID loadId) {
        load(loadId);
        return jdbc.query("SELECT id,study_id,issue_code,severity,field_name,issue_message,recorded_at FROM warehouse_quality_issues WHERE load_id=? ORDER BY severity DESC,issue_code,recorded_at,id",
                (row, index) -> new QualityIssueView(uuid(row.getBytes("id")), nullableUuid(row.getBytes("study_id")), row.getString("issue_code"),
                        row.getString("severity"), row.getString("field_name"), row.getString("issue_message"), instant(row.getTimestamp("recorded_at"))),
                bytes(loadId));
    }

    public ActorScope requireActor(String email, boolean curatorAuthority) {
        List<byte[]> departments = jdbc.query("SELECT department_id FROM user_accounts WHERE LOWER(email)=LOWER(?) AND account_status='ACTIVE'",
                (row, index) -> row.getBytes(1), email);
        if (departments.isEmpty()) throw new AccessDeniedException("The authenticated account is not active.");
        int curatorRows = count("SELECT COUNT(*) FROM user_accounts u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id "
                + "WHERE LOWER(u.email)=LOWER(?) AND r.code='CURATOR'", email);
        return new ActorScope(email.toLowerCase(Locale.ROOT), departments.getFirst(), curatorAuthority && curatorRows > 0);
    }

    public AnalyticsView analytics(ActorScope scope, AnalyticsFilters filters) {
        Optional<SnapshotRow> latest = latestSnapshot();
        if (latest.isEmpty()) return AnalyticsView.unassessed(filters);
        SnapshotRow snapshot = latest.get();
        FilterSql filter = studyFilter(snapshot.id(), scope, filters);
        int visible = count("SELECT COUNT(DISTINCT s.study_id) FROM dw_study_dimensions s WHERE " + filter.sql(), filter.args().toArray());
        int unavailableYear = count("SELECT COUNT(DISTINCT s.study_id) FROM dw_study_dimensions s WHERE " + filter.sql() + " AND s.completion_year IS NULL",
                filter.args().toArray());
        List<YearCount> years = jdbc.query("SELECT s.completion_year,COUNT(DISTINCT s.study_id) AS study_count FROM dw_study_dimensions s WHERE " + filter.sql()
                        + " AND s.completion_year IS NOT NULL GROUP BY s.completion_year ORDER BY s.completion_year",
                (row, index) -> new YearCount(row.getInt("completion_year"), row.getInt("study_count")), filter.args().toArray());
        List<DepartmentCount> departments = jdbc.query("SELECT COALESCE(d.department_code,'UNAVAILABLE') AS department_code,COALESCE(d.department_name,'Unavailable') AS department_name,COUNT(DISTINCT s.study_id) AS study_count "
                        + "FROM dw_study_dimensions s LEFT JOIN dw_department_dimensions d ON d.snapshot_id=s.snapshot_id AND d.department_id=s.department_id WHERE " + filter.sql()
                        + " GROUP BY d.department_code,d.department_name ORDER BY study_count DESC,department_name",
                (row, index) -> new DepartmentCount(row.getString("department_code"), row.getString("department_name"), row.getInt("study_count")),
                filter.args().toArray());
        List<TopicCount> repeated = topicCounts(filter, "t.term_type IN ('TOPIC','KEYWORD')", true);
        List<TopicCount> researchAreas = topicCounts(filter, "t.term_type='RESEARCH_AREA'", false);
        List<TopicTrend> trends = topicTrends(filter);
        int authorizedSource = scope.curator() && noFilters(filters) ? snapshot.sourceCount() : visible;
        return new AnalyticsView(snapshot.id(), snapshot.publishedAt(), "ASSESSED", filters, authorizedSource, visible,
                unavailableYear, years, departments, repeated, researchAreas, trends, scopedQuality(snapshot.loadId(), snapshot.id(), scope));
    }

    public ContinuationHistoryView continuationHistory(ActorScope scope, int requestedLimit) {
        Optional<SnapshotRow> latest = latestSnapshot();
        if (latest.isEmpty()) return ContinuationHistoryView.unassessed();
        SnapshotRow snapshot = latest.get();
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<Object> arguments = new ArrayList<>();
        arguments.add(bytes(snapshot.id()));
        StringBuilder where = new StringBuilder("f.snapshot_id=?");
        appendVisibleStudy(where, arguments, "source", scope);
        appendVisibleStudy(where, arguments, "target", scope);
        if (!scope.curator()) {
            where.append(" AND (f.successor_project_id IS NULL OR EXISTS(SELECT 1 FROM project_memberships pm JOIN user_accounts u ON u.id=pm.user_id JOIN projects p ON p.id=pm.project_id WHERE pm.project_id=f.successor_project_id AND LOWER(u.email)=LOWER(?) AND u.account_status='ACTIVE' AND u.department_id=p.department_id))");
            arguments.add(scope.email());
        }
        int total = count("SELECT COUNT(*) FROM dw_continuation_facts f LEFT JOIN dw_study_dimensions source ON source.snapshot_id=f.snapshot_id AND source.study_id=f.source_study_id "
                + "LEFT JOIN dw_study_dimensions target ON target.snapshot_id=f.snapshot_id AND target.study_id=f.target_study_id WHERE " + where,
                arguments.toArray());
        List<ContinuationHistoryItem> items = jdbc.query("SELECT f.fact_key,f.source_kind,f.source_study_id,source.title AS source_title,f.target_study_id,target.title AS target_title,"
                        + "f.successor_project_id,f.continuation_item_id,f.relationship_type,f.evidence_status,f.rationale_text,f.evidence_at FROM dw_continuation_facts f "
                        + "LEFT JOIN dw_study_dimensions source ON source.snapshot_id=f.snapshot_id AND source.study_id=f.source_study_id "
                        + "LEFT JOIN dw_study_dimensions target ON target.snapshot_id=f.snapshot_id AND target.study_id=f.target_study_id WHERE " + where
                        + " ORDER BY f.evidence_at DESC,f.source_kind,f.fact_key LIMIT " + limit,
                (row, index) -> new ContinuationHistoryItem(row.getString("fact_key"), row.getString("source_kind"),
                        nullableUuid(row.getBytes("source_study_id")), row.getString("source_title"), nullableUuid(row.getBytes("target_study_id")),
                        row.getString("target_title"), nullableUuid(row.getBytes("successor_project_id")), nullableUuid(row.getBytes("continuation_item_id")),
                        row.getString("relationship_type"), row.getString("evidence_status"), row.getString("rationale_text"),
                        instant(row.getTimestamp("evidence_at"))), arguments.toArray());
        return new ContinuationHistoryView(snapshot.id(), snapshot.publishedAt(), "ASSESSED", total, items);
    }

    private void collectContinuity(UUID loadId) {
        List<ContinuitySource> rows = new ArrayList<>();
        rows.addAll(jdbc.query("SELECT id,source_study_id,target_study_id,relationship_type,rationale,created_at FROM study_relationships ORDER BY id",
                (row, index) -> new ContinuitySource("STUDY_RELATIONSHIP", uuid(row.getBytes("id")), row.getBytes("source_study_id"),
                        row.getBytes("target_study_id"), null, null, row.getString("relationship_type"), "RECORDED",
                        row.getString("rationale"), row.getTimestamp("created_at"))));
        rows.addAll(jdbc.query("SELECT pp.project_id,pp.study_id,target.id AS target_study_id,pp.lineage_type,pp.rationale,p.created_at FROM project_predecessors pp "
                        + "JOIN projects p ON p.id=pp.project_id LEFT JOIN studies target ON target.source_project_id=pp.project_id ORDER BY pp.project_id,pp.study_id",
                (row, index) -> new ContinuitySource("PROJECT_PREDECESSOR", stableId("project-predecessor:" + uuid(row.getBytes("project_id")) + ":" + uuid(row.getBytes("study_id"))),
                        row.getBytes("study_id"), row.getBytes("target_study_id"), row.getBytes("project_id"), null,
                        row.getString("lineage_type"), "APPROVED", row.getString("rationale"), row.getTimestamp("created_at"))));
        rows.addAll(jdbc.query("SELECT c.id,c.project_id,ci.study_id,target.id AS target_study_id,ci.id AS continuation_item_id,c.claim_status,c.claim_rationale,c.claimed_at FROM continuation_item_claims c "
                        + "JOIN continuation_items ci ON ci.id=c.continuation_item_id LEFT JOIN studies target ON target.source_project_id=c.project_id ORDER BY c.id",
                (row, index) -> new ContinuitySource("CONTINUATION_CLAIM", uuid(row.getBytes("id")), row.getBytes("study_id"),
                        row.getBytes("target_study_id"), row.getBytes("project_id"), row.getBytes("continuation_item_id"), "CLAIMS",
                        row.getString("claim_status"), row.getString("claim_rationale"), row.getTimestamp("claimed_at"))));
        rows.addAll(jdbc.query("SELECT o.id,c.project_id,ci.study_id,target.id AS target_study_id,ci.id AS continuation_item_id,o.outcome_status,o.outcome_summary,o.recorded_at FROM continuation_claim_outcomes o "
                        + "JOIN continuation_item_claims c ON c.id=o.claim_id JOIN continuation_items ci ON ci.id=c.continuation_item_id "
                        + "LEFT JOIN studies target ON target.source_project_id=c.project_id ORDER BY o.id",
                (row, index) -> new ContinuitySource("CLAIM_OUTCOME", uuid(row.getBytes("id")), row.getBytes("study_id"),
                        row.getBytes("target_study_id"), row.getBytes("project_id"), row.getBytes("continuation_item_id"), "OUTCOME",
                        row.getString("outcome_status"), row.getString("outcome_summary"), row.getTimestamp("recorded_at"))));
        rows.addAll(jdbc.query("SELECT e.id,c.project_id,ci.study_id,target.id AS target_study_id,ci.id AS continuation_item_id,e.outcome_status,e.outcome_summary,e.recorded_at FROM continuation_claim_events e "
                        + "JOIN continuation_item_claims c ON c.id=e.claim_id JOIN continuation_items ci ON ci.id=c.continuation_item_id "
                        + "LEFT JOIN studies target ON target.source_project_id=c.project_id ORDER BY e.id",
                (row, index) -> new ContinuitySource("CLAIM_EVENT", uuid(row.getBytes("id")), row.getBytes("study_id"),
                        row.getBytes("target_study_id"), row.getBytes("project_id"), row.getBytes("continuation_item_id"), "OUTCOME_EVENT",
                        row.getString("outcome_status"), row.getString("outcome_summary"), row.getTimestamp("recorded_at"))));
        rows.addAll(jdbc.query("SELECT s.id,s.source_project_id,s.published_at,s.created_at FROM studies s WHERE s.source_project_id IS NOT NULL ORDER BY s.id",
                (row, index) -> new ContinuitySource("COMPLETION_LINK", uuid(row.getBytes("id")), null, row.getBytes("id"),
                        row.getBytes("source_project_id"), null, "PUBLISHED_FROM_PROJECT", "RECORDED", null,
                        row.getTimestamp("published_at") == null ? row.getTimestamp("created_at") : row.getTimestamp("published_at"))));
        for (ContinuitySource row : rows) {
            String factKey = sha256(row.kind() + ":" + row.id());
            jdbc.update("INSERT INTO warehouse_staged_continuity(load_id,fact_key,source_kind,source_study_id,target_study_id,successor_project_id,continuation_item_id,relationship_type,evidence_status,rationale_text,evidence_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    bytes(loadId), factKey, row.kind(), row.sourceStudy(), row.targetStudy(), row.project(), row.item(), row.relationship(),
                    row.status(), row.rationale(), row.at());
        }
    }

    private void validateDuplicateIdentifier(UUID loadId, String column, String code, String field) {
        String sql = "SELECT " + column + " FROM warehouse_staged_studies WHERE load_id=? AND " + column
                + " IS NOT NULL AND TRIM(" + column + ")<>'' GROUP BY " + column + " HAVING COUNT(*)>1";
        List<String> duplicates = jdbc.query(sql, (row, index) -> row.getString(1), bytes(loadId));
        for (String duplicate : duplicates) {
            List<UUID> studies = jdbc.query("SELECT study_id FROM warehouse_staged_studies WHERE load_id=? AND " + column + "=? ORDER BY study_id",
                    (row, index) -> uuid(row.getBytes(1)), bytes(loadId), duplicate);
            for (UUID studyId : studies) issue(loadId, studyId, code, "WARNING", field,
                    "The identifier occurs in more than one staged study; no record was merged automatically.");
        }
    }

    private void validateTopicTypes(UUID loadId) {
        List<TopicValidation> topics = jdbc.query("SELECT study_id,term_type,active FROM warehouse_staged_topics WHERE load_id=? ORDER BY study_id,term_id",
                (row, index) -> new TopicValidation(uuid(row.getBytes("study_id")), upper(row.getString("term_type")), row.getBoolean("active")),
                bytes(loadId));
        for (TopicValidation row : topics) {
            if (!ANALYTIC_TERM_TYPES.contains(row.type())) {
                issue(loadId, row.studyId(), "TERM_TYPE_NOT_ANALYTIC", "INFO", "researchArea",
                        "The assigned taxonomy term is preserved but excluded from topic and research-area analytics.");
            }
            if (!row.active()) {
                issue(loadId, row.studyId(), "INACTIVE_TAXONOMY_TERM", "INFO", "researchArea",
                        "The inactive taxonomy assignment is preserved in staging but excluded from current analytics.");
            }
        }
    }

    private List<TopicCount> topicCounts(FilterSql filter, String termPredicate, boolean repeatedOnly) {
        return jdbc.query("SELECT MIN(t.canonical_label) AS label,t.term_type,COUNT(DISTINCT s.study_id) AS study_count FROM dw_study_dimensions s "
                        + "JOIN dw_study_topic_bridge b ON b.snapshot_id=s.snapshot_id AND b.study_id=s.study_id "
                        + "JOIN dw_topic_dimensions t ON t.snapshot_id=b.snapshot_id AND t.term_id=b.term_id WHERE " + filter.sql() + " AND " + termPredicate
                        + " GROUP BY t.normalized_label,t.term_type " + (repeatedOnly ? "HAVING COUNT(DISTINCT s.study_id)>=2 " : "")
                        + "ORDER BY study_count DESC,label LIMIT 100",
                (row, index) -> new TopicCount(null, row.getString("label"), row.getString("term_type"), row.getInt("study_count")),
                filter.args().toArray());
    }

    private List<TopicTrend> topicTrends(FilterSql filter) {
        return jdbc.query("SELECT MIN(t.canonical_label) AS label,t.term_type,s.completion_year,COUNT(DISTINCT s.study_id) AS study_count FROM dw_study_dimensions s "
                        + "JOIN dw_study_topic_bridge b ON b.snapshot_id=s.snapshot_id AND b.study_id=s.study_id "
                        + "JOIN dw_topic_dimensions t ON t.snapshot_id=b.snapshot_id AND t.term_id=b.term_id WHERE " + filter.sql()
                        + " AND t.term_type IN ('TOPIC','KEYWORD','RESEARCH_AREA') AND s.completion_year IS NOT NULL "
                        + "GROUP BY t.normalized_label,t.term_type,s.completion_year ORDER BY s.completion_year,label LIMIT 500",
                (row, index) -> new TopicTrend(null, row.getString("label"), row.getString("term_type"),
                        row.getInt("completion_year"), row.getInt("study_count")), filter.args().toArray());
    }

    private FilterSql studyFilter(UUID snapshotId, ActorScope scope, AnalyticsFilters filters) {
        StringBuilder sql = new StringBuilder("s.snapshot_id=?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(snapshotId));
        if (!scope.curator()) {
            sql.append(" AND (s.visibility IN ('PUBLIC','CAMPUS') OR (s.department_id=? AND s.visibility NOT IN ('RESTRICTED','EMBARGOED')))");
            args.add(scope.departmentId());
        }
        if (filters.department() != null && !filters.department().isBlank()) {
            sql.append(" AND EXISTS(SELECT 1 FROM dw_department_dimensions fd WHERE fd.snapshot_id=s.snapshot_id AND fd.department_id=s.department_id AND (LOWER(fd.department_code)=LOWER(?) OR LOWER(fd.department_name)=LOWER(?)))");
            args.add(filters.department().strip());
            args.add(filters.department().strip());
        }
        if (filters.fromYear() != null) {
            sql.append(" AND s.completion_year>=?");
            args.add(filters.fromYear());
        }
        if (filters.toYear() != null) {
            sql.append(" AND s.completion_year<=?");
            args.add(filters.toYear());
        }
        return new FilterSql(sql.toString(), args);
    }

    private void appendVisibleStudy(StringBuilder where, List<Object> arguments, String alias, ActorScope scope) {
        where.append(" AND (f.").append(alias).append("_study_id IS NULL OR ");
        if (scope.curator()) {
            where.append(alias).append(".study_id IS NOT NULL)");
        } else {
            where.append("(").append(alias).append(".study_id IS NOT NULL AND (")
                    .append(alias).append(".visibility IN ('PUBLIC','CAMPUS') OR (")
                    .append(alias).append(".department_id=? AND ").append(alias)
                    .append(".visibility NOT IN ('RESTRICTED','EMBARGOED')))))");
            arguments.add(scope.departmentId());
        }
    }

    private QualitySummary scopedQuality(UUID loadId, UUID snapshotId, ActorScope scope) {
        if (scope.curator()) return qualitySummary(loadId);
        String visibility = "(s.visibility IN ('PUBLIC','CAMPUS') OR (s.department_id=? AND s.visibility NOT IN ('RESTRICTED','EMBARGOED')))";
        List<QualityCount> severity = jdbc.query("SELECT q.severity,COUNT(*) AS issue_count FROM warehouse_quality_issues q "
                        + "LEFT JOIN dw_study_dimensions s ON s.snapshot_id=? AND s.study_id=q.study_id WHERE q.load_id=? AND q.study_id IS NOT NULL AND " + visibility
                        + " GROUP BY q.severity ORDER BY q.severity",
                (row, index) -> new QualityCount(row.getString(1), row.getInt(2)), bytes(snapshotId), bytes(loadId), scope.departmentId());
        List<QualityCount> codes = jdbc.query("SELECT q.issue_code,COUNT(*) AS issue_count FROM warehouse_quality_issues q "
                        + "LEFT JOIN dw_study_dimensions s ON s.snapshot_id=? AND s.study_id=q.study_id WHERE q.load_id=? AND q.study_id IS NOT NULL AND " + visibility
                        + " GROUP BY q.issue_code ORDER BY q.issue_code",
                (row, index) -> new QualityCount(row.getString(1), row.getInt(2)), bytes(snapshotId), bytes(loadId), scope.departmentId());
        int allStudies = count("SELECT COUNT(*) FROM dw_study_dimensions WHERE snapshot_id=?", bytes(snapshotId));
        int visibleStudies = count("SELECT COUNT(*) FROM dw_study_dimensions s WHERE s.snapshot_id=? AND " + visibility,
                bytes(snapshotId), scope.departmentId());
        return qualitySummary(allStudies == visibleStudies ? "ASSESSED" : "PARTIAL", severity, codes);
    }

    private QualitySummary qualitySummary(UUID loadId) {
        List<QualityCount> severity = jdbc.query("SELECT severity,COUNT(*) FROM warehouse_quality_issues WHERE load_id=? GROUP BY severity ORDER BY severity",
                (row, index) -> new QualityCount(row.getString(1), row.getInt(2)), bytes(loadId));
        List<QualityCount> codes = jdbc.query("SELECT issue_code,COUNT(*) FROM warehouse_quality_issues WHERE load_id=? GROUP BY issue_code ORDER BY issue_code",
                (row, index) -> new QualityCount(row.getString(1), row.getInt(2)), bytes(loadId));
        return qualitySummary("ASSESSED", severity, codes);
    }

    private static QualitySummary qualitySummary(String status, List<QualityCount> severity, List<QualityCount> codes) {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        severity.forEach(row -> bySeverity.put(row.key(), row.count()));
        Map<String, Integer> byCode = new LinkedHashMap<>();
        codes.forEach(row -> byCode.put(row.key(), row.count()));
        return new QualitySummary(status, severity.stream().mapToInt(QualityCount::count).sum(), Map.copyOf(bySeverity), Map.copyOf(byCode));
    }

    private LoadView mapLoad(ResultSet row) throws SQLException {
        UUID id = uuid(row.getBytes("id"));
        List<StageView> stages = jdbc.query("SELECT stage_code,stage_order,stage_status,input_count,output_count,details_json,started_at,completed_at FROM warehouse_load_stages WHERE load_id=? ORDER BY stage_order",
                (stage, index) -> new StageView(stage.getString("stage_code"), stage.getInt("stage_order"), stage.getString("stage_status"),
                        stage.getInt("input_count"), stage.getInt("output_count"), stage.getString("details_json"),
                        instant(stage.getTimestamp("started_at")), instant(stage.getTimestamp("completed_at"))), bytes(id));
        String status = row.getString("load_status");
        String assessment = switch (status) {
            case "PUBLISHED", "UNCHANGED" -> "ASSESSED";
            case "FAILED" -> "PARTIAL";
            default -> "UNASSESSED";
        };
        UUID snapshotId = nullableUuid(row.getBytes("published_snapshot_id"));
        UUID qualityLoadId = id;
        if ("UNCHANGED".equals(status) && snapshotId != null) {
            qualityLoadId = jdbc.queryForObject("SELECT load_id FROM warehouse_snapshots WHERE id=?", (result, index) -> uuid(result.getBytes(1)), bytes(snapshotId));
        }
        QualitySummary quality = qualitySummary(qualityLoadId);
        if (!"ASSESSED".equals(assessment)) {
            quality = new QualitySummary(assessment, quality.issueCount(), quality.bySeverity(), quality.byCode());
        }
        return new LoadView(id, status, row.getString("current_stage"), assessment, row.getString("source_sha256"),
                row.getInt("source_count"), row.getInt("accepted_count"), row.getInt("rejected_count"),
                snapshotId, instant(row.getTimestamp("source_cutoff_at")),
                instant(row.getTimestamp("started_at")), instant(row.getTimestamp("completed_at")), row.getString("failure_reason"),
                stages, quality);
    }

    private Optional<SnapshotRow> latestSnapshot() {
        return jdbc.query("SELECT id,load_id,source_study_count,published_at FROM warehouse_snapshots WHERE snapshot_status='PUBLISHED' ORDER BY published_at DESC,id DESC LIMIT 1",
                (row, index) -> new SnapshotRow(uuid(row.getBytes("id")), uuid(row.getBytes("load_id")), row.getInt("source_study_count"),
                        instant(row.getTimestamp("published_at")))).stream().findFirst();
    }

    private LoadSource loadSource(UUID loadId) {
        try {
            return jdbc.queryForObject("SELECT source_sha256,source_cutoff_at,source_count,accepted_count,rejected_count FROM warehouse_loads WHERE id=? AND load_status='RUNNING'",
                    (row, index) -> new LoadSource(row.getString("source_sha256"), instant(row.getTimestamp("source_cutoff_at")),
                            row.getInt("source_count"), row.getInt("accepted_count"), row.getInt("rejected_count")), bytes(loadId));
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Warehouse load is not running: " + loadId, exception);
        }
    }

    private void insertStagedStudy(UUID loadId, StagedStudy s) {
        jdbc.update("INSERT INTO warehouse_staged_studies(load_id,study_id,department_id,department_code,department_name,institutional_code,doi,repository_identifier,program_name,title,normalized_title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,results_text,academic_year,source_completion_year,completion_year,lifecycle_status,visibility,published_at,archived_at,source_row_version,source_created_at,valid_record,objective_count,topic_count,continuation_count,retrieval_count,transformed_sha256) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(loadId), s.id(), s.departmentId(), s.departmentCode(), s.departmentName(), s.institutionalCode(), s.doi(),
                s.repositoryIdentifier(), s.programName(), s.title(), null, s.abstractText(), s.problemStatement(), s.methodology(),
                s.features(), s.dataSources(), s.technology(), s.intendedUsers(), s.stakeholders(), s.siteContext(), s.keywords(),
                s.results(), s.academicYear(), s.sourceCompletionYear(), null, s.lifecycle(), s.visibility(), s.publishedAt(), s.archivedAt(), s.rowVersion(), s.createdAt(),
                true, 0, 0, 0, 0, null);
    }

    private static StagedStudy stagedStudy(ResultSet row) throws SQLException {
        return new StagedStudy(row.getBytes("id"), row.getBytes("department_id"), row.getString("department_code"), row.getString("department_name"),
                row.getString("institutional_code"), row.getString("doi"), row.getString("repository_identifier"), row.getString("program_name"),
                row.getString("title"), row.getString("abstract_text"), row.getString("problem_statement"), row.getString("methodology"),
                row.getString("features_text"), row.getString("data_sources_text"), row.getString("technology_text"), row.getString("intended_users_text"),
                row.getString("stakeholders_text"), row.getString("site_context"), row.getString("keywords_text"), row.getString("results_text"), row.getString("academic_year"),
                nullableInteger(row, "completion_year"), row.getString("lifecycle_status"), row.getString("visibility"), row.getTimestamp("published_at"), row.getTimestamp("archived_at"),
                row.getLong("row_version"), row.getTimestamp("created_at"));
    }

    private void issue(UUID loadId, UUID studyId, String code, String severity, String field, String message) {
        jdbc.update("INSERT INTO warehouse_quality_issues(id,load_id,study_id,issue_code,severity,field_name,issue_message,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(loadId), studyId == null ? null : bytes(studyId), code, severity, field, message,
                Timestamp.from(Instant.now()));
    }

    private String hashStaged(UUID loadId) {
        MessageDigest digest = digest();
        digestRows(digest, "STUDIES", "SELECT study_id,department_id,institutional_code,doi,repository_identifier,program_name,title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,results_text,academic_year,source_completion_year,lifecycle_status,visibility,published_at,archived_at,source_row_version,source_created_at FROM warehouse_staged_studies WHERE load_id=? ORDER BY study_id", loadId);
        digestRows(digest, "OBJECTIVES", "SELECT study_id,objective_id,objective_order,statement_text FROM warehouse_staged_objectives WHERE load_id=? ORDER BY study_id,objective_order,objective_id", loadId);
        digestRows(digest, "METADATA_VERSIONS", "SELECT metadata_version_id,study_id,version_number,provenance_type,source_sha256,metadata_json,recorded_at FROM warehouse_staged_metadata_versions WHERE load_id=? ORDER BY study_id,version_number,metadata_version_id", loadId);
        digestRows(digest, "TOPICS", "SELECT study_id,term_id,term_type,canonical_label,active FROM warehouse_staged_topics WHERE load_id=? ORDER BY study_id,term_id", loadId);
        digestRows(digest, "RETRIEVAL", "SELECT discovery_run_id,study_id,candidate_rank,problem_score,objective_score,solution_score,confidence_score,similarity_band,exact_match,run_status,algorithm_version,run_started_at,run_completed_at FROM warehouse_staged_retrievals WHERE load_id=? ORDER BY discovery_run_id,study_id", loadId);
        digestRows(digest, "CONTINUITY", "SELECT fact_key,source_kind,source_study_id,target_study_id,successor_project_id,continuation_item_id,relationship_type,evidence_status,rationale_text,evidence_at FROM warehouse_staged_continuity WHERE load_id=? ORDER BY fact_key", loadId);
        return HexFormat.of().formatHex(digest.digest());
    }

    private String hashStudy(UUID loadId, byte[] studyId) {
        MessageDigest digest = digest();
        digestRows(digest, "STUDY", "SELECT study_id,department_id,institutional_code,doi,repository_identifier,program_name,title,normalized_title,abstract_text,problem_statement,methodology,features_text,data_sources_text,technology_text,intended_users_text,stakeholders_text,site_context,keywords_text,results_text,academic_year,source_completion_year,completion_year,lifecycle_status,visibility,published_at,archived_at,source_row_version,source_created_at,objective_count,topic_count,continuation_count,retrieval_count FROM warehouse_staged_studies WHERE load_id=? AND study_id=?", loadId, studyId);
        digestRows(digest, "OBJECTIVES", "SELECT objective_id,objective_order,statement_text,normalized_statement FROM warehouse_staged_objectives WHERE load_id=? AND study_id=? ORDER BY objective_order,objective_id", loadId, studyId);
        digestRows(digest, "METADATA_VERSIONS", "SELECT metadata_version_id,version_number,provenance_type,source_sha256,metadata_json,recorded_at FROM warehouse_staged_metadata_versions WHERE load_id=? AND study_id=? ORDER BY version_number,metadata_version_id", loadId, studyId);
        digestRows(digest, "TOPICS", "SELECT term_id,term_type,canonical_label,normalized_label,active FROM warehouse_staged_topics WHERE load_id=? AND study_id=? ORDER BY term_id", loadId, studyId);
        return HexFormat.of().formatHex(digest.digest());
    }

    private void digestRows(MessageDigest digest, String marker, String sql, UUID loadId, Object... remaining) {
        updateDigest(digest, marker);
        Object[] args = new Object[remaining.length + 1];
        args[0] = bytes(loadId);
        System.arraycopy(remaining, 0, args, 1, remaining.length);
        jdbc.query(sql, (RowCallbackHandler) row -> {
            int columns = row.getMetaData().getColumnCount();
            for (int column = 1; column <= columns; column++) updateDigest(digest, canonical(row.getObject(column)));
            updateDigest(digest, "\n");
        }, args);
    }

    private static String canonical(Object value) {
        if (value == null) return "<NULL>";
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 31);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static String normalize(String value, boolean lowerCase) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        return lowerCase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean noFilters(AnalyticsFilters filters) {
        return (filters.department() == null || filters.department().isBlank()) && filters.fromYear() == null && filters.toYear() == null;
    }

    private static void requireStage(String stage) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Unsupported warehouse stage: " + stage);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256(String value) {
        MessageDigest digest = digest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(("ugnay:warehouse:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID nullableUuid(byte[] value) { return value == null ? null : uuid(value); }
    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String abbreviate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    public record Collected(int studyCount, int sourceRows, String sourceHash) {}
    public record ValidationResult(int sourceCount, int acceptedCount, int rejectedCount, int issueCount) {}
    public record SnapshotResult(int studyCount, int yearCount, int departmentCount, int topicCount, int continuationCount) {}
    public record ActorScope(String email, byte[] departmentId, boolean curator) {
        public ActorScope { departmentId = departmentId == null ? null : departmentId.clone(); }
        @Override public byte[] departmentId() { return departmentId == null ? null : departmentId.clone(); }
    }

    private record ValidationStudy(UUID id, String title, String academicYear, Integer sourceCompletionYear, String lifecycleStatus, String visibility,
                                   byte[] departmentId, String institutionalCode) {}
    private record StudyText(byte[] id, String text) {}
    private record StudyChildText(byte[] studyId, byte[] childId, String text) {}
    private record ObjectiveSource(byte[] id, byte[] studyId, int order, String statement) {}
    private record MetadataVersionSource(byte[] id, byte[] studyId, int version, String provenance, String sourceHash,
                                         String metadataJson, Timestamp recordedAt) {}
    private record TopicSource(byte[] studyId, byte[] id, String type, String label, boolean active) {}
    private record TopicValidation(UUID studyId, String type, boolean active) {}
    private record RetrievalSource(byte[] runId, byte[] studyId, int rank, BigDecimal problem, BigDecimal objective,
                                   BigDecimal solution, BigDecimal confidence, String band, boolean exact, String status,
                                   String algorithm, Timestamp startedAt, Timestamp completedAt) {}
    private record QualityCount(String key, int count) {}
    private record FilterSql(String sql, List<Object> args) {}
    private record SnapshotRow(UUID id, UUID loadId, int sourceCount, Instant publishedAt) {}
    private record LoadSource(String sourceHash, Instant cutoff, int sourceCount, int acceptedCount, int rejectedCount) {}
    private record ContinuitySource(String kind, UUID id, byte[] sourceStudy, byte[] targetStudy, byte[] project, byte[] item,
                                    String relationship, String status, String rationale, Timestamp at) {}
    private record StagedStudy(byte[] id, byte[] departmentId, String departmentCode, String departmentName,
                               String institutionalCode, String doi, String repositoryIdentifier, String programName,
                               String title, String abstractText, String problemStatement, String methodology, String features,
                               String dataSources, String technology, String intendedUsers, String stakeholders, String siteContext,
                               String keywords, String results, String academicYear, Integer sourceCompletionYear, String lifecycle, String visibility, Timestamp publishedAt,
                               Timestamp archivedAt, long rowVersion, Timestamp createdAt) {}
}

-- Normalized persistence support for the pilot workspace projections.  V1 owns
-- the domain entities; this migration only adds attributes and child rows that
-- the application records expose but V1 could not reconstruct after restart.

ALTER TABLE projects ADD COLUMN current_baseline_id BINARY(16);
ALTER TABLE projects ADD COLUMN updated_at DATETIME(6);
ALTER TABLE project_baselines ADD CONSTRAINT uq_baseline_project_id UNIQUE (project_id, id);
ALTER TABLE projects ADD CONSTRAINT fk_project_current_baseline
    FOREIGN KEY (current_baseline_id) REFERENCES project_baselines(id);
ALTER TABLE projects ADD CONSTRAINT fk_project_current_baseline_scope
    FOREIGN KEY (id, current_baseline_id) REFERENCES project_baselines(project_id, id);

ALTER TABLE discovery_runs ADD COLUMN semantic_provider VARCHAR(160);
ALTER TABLE candidate_evidence ADD COLUMN proposal_excerpt TEXT;
ALTER TABLE candidate_evidence ADD COLUMN study_excerpt TEXT;
ALTER TABLE trace_item_revisions ADD COLUMN readiness_score DECIMAL(5,2) NOT NULL DEFAULT 0;
ALTER TABLE test_executions ADD COLUMN evidence_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE impact_paths ADD COLUMN reason_text TEXT;
ALTER TABLE health_snapshots ADD COLUMN open_findings INT NOT NULL DEFAULT 0;
ALTER TABLE health_snapshots ADD COLUMN critical_findings INT NOT NULL DEFAULT 0;
ALTER TABLE health_snapshots ADD COLUMN rule_version VARCHAR(80) NOT NULL DEFAULT 'alignment-v1';

CREATE TABLE discovery_revision_checklist (
    discovery_run_id BINARY(16) NOT NULL,
    checklist_order INT NOT NULL,
    checklist_text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (discovery_run_id, checklist_order),
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id)
);

CREATE TABLE candidate_evidence_components (
    id BINARY(16) PRIMARY KEY,
    candidate_evidence_id BINARY(16) NOT NULL,
    component_order INT NOT NULL,
    component_name VARCHAR(64) NOT NULL,
    raw_score DECIMAL(7,3) NOT NULL,
    score_weight DECIMAL(7,4) NOT NULL,
    weighted_score DECIMAL(7,3) NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    UNIQUE (candidate_evidence_id, component_order),
    FOREIGN KEY (candidate_evidence_id) REFERENCES candidate_evidence(id)
);

CREATE TABLE candidate_component_terms (
    component_id BINARY(16) NOT NULL,
    term_order INT NOT NULL,
    matched_term VARCHAR(300) NOT NULL,
    PRIMARY KEY (component_id, term_order),
    FOREIGN KEY (component_id) REFERENCES candidate_evidence_components(id)
);

CREATE TABLE project_team_members (
    project_id BINARY(16) NOT NULL,
    member_order INT NOT NULL,
    display_name VARCHAR(180) NOT NULL,
    PRIMARY KEY (project_id, member_order),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE trace_coverage_snapshots (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_id BINARY(16) NOT NULL,
    assessment_status VARCHAR(32) NOT NULL,
    mapped_coverage DECIMAL(5,2) NOT NULL,
    executed_coverage DECIMAL(5,2) NOT NULL,
    passing_coverage DECIMAL(5,2) NOT NULL,
    priority_weighted_passing_coverage DECIMAL(5,2) NOT NULL,
    total_requirements INT NOT NULL,
    verified_requirements INT NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE scope_risk_snapshots (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_id BINARY(16),
    assessment_status VARCHAR(32) NOT NULL,
    risk_score INT,
    risk_band VARCHAR(32),
    governance_score INT NOT NULL,
    alignment_score INT NOT NULL,
    controlled_growth_score INT NOT NULL,
    boundary_score INT NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE scope_risk_explanations (
    scope_risk_snapshot_id BINARY(16) NOT NULL,
    explanation_order INT NOT NULL,
    explanation_text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (scope_risk_snapshot_id, explanation_order),
    FOREIGN KEY (scope_risk_snapshot_id) REFERENCES scope_risk_snapshots(id)
);

CREATE TABLE change_request_boundary_flags (
    change_request_id BINARY(16) NOT NULL,
    flag_order INT NOT NULL,
    boundary_flag VARCHAR(80) NOT NULL,
    PRIMARY KEY (change_request_id, flag_order),
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id)
);

CREATE TABLE impact_previews (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL UNIQUE,
    based_on_baseline_id BINARY(16) NOT NULL,
    baseline_current BOOLEAN NOT NULL,
    scope_risk_snapshot_id BINARY(16),
    calculated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (based_on_baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (scope_risk_snapshot_id) REFERENCES scope_risk_snapshots(id)
);

CREATE TABLE impact_path_nodes (
    impact_path_id BINARY(16) NOT NULL,
    node_order INT NOT NULL,
    trace_item_id BINARY(16) NOT NULL,
    PRIMARY KEY (impact_path_id, node_order),
    FOREIGN KEY (impact_path_id) REFERENCES impact_paths(id),
    FOREIGN KEY (trace_item_id) REFERENCES trace_items(id)
);

CREATE TABLE impact_documents_to_revise (
    impact_preview_id BINARY(16) NOT NULL,
    document_order INT NOT NULL,
    document_label VARCHAR(300) NOT NULL,
    PRIMARY KEY (impact_preview_id, document_order),
    FOREIGN KEY (impact_preview_id) REFERENCES impact_previews(id)
);

CREATE TABLE completion_criteria (
    completion_package_id BINARY(16) NOT NULL,
    criterion_order INT NOT NULL,
    criterion_key VARCHAR(80) NOT NULL,
    criterion_label VARCHAR(300) NOT NULL,
    criterion_weight INT NOT NULL,
    completion_ratio DECIMAL(6,4) NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    PRIMARY KEY (completion_package_id, criterion_order),
    UNIQUE (completion_package_id, criterion_key),
    FOREIGN KEY (completion_package_id) REFERENCES completion_packages(id)
);

CREATE TABLE lineage_nodes (
    id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    node_order INT NOT NULL,
    node_kind VARCHAR(32) NOT NULL,
    title VARCHAR(600) NOT NULL,
    node_status VARCHAR(32) NOT NULL,
    academic_year VARCHAR(16),
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (project_id, id),
    UNIQUE (project_id, node_order),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE lineage_edges (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    source_node_id BINARY(16) NOT NULL,
    target_node_id BINARY(16) NOT NULL,
    lineage_type VARCHAR(32) NOT NULL,
    primary_lineage BOOLEAN NOT NULL DEFAULT FALSE,
    rationale TEXT NOT NULL,
    UNIQUE (project_id, source_node_id, target_node_id, lineage_type),
    FOREIGN KEY (project_id, source_node_id) REFERENCES lineage_nodes(project_id, id),
    FOREIGN KEY (project_id, target_node_id) REFERENCES lineage_nodes(project_id, id)
);

CREATE TABLE health_dimensions (
    health_snapshot_id BINARY(16) NOT NULL,
    dimension_order INT NOT NULL,
    dimension_key VARCHAR(80) NOT NULL,
    label VARCHAR(180) NOT NULL,
    assessment_status VARCHAR(32) NOT NULL,
    dimension_score DECIMAL(5,2),
    health_band VARCHAR(32) NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    PRIMARY KEY (health_snapshot_id, dimension_order),
    UNIQUE (health_snapshot_id, dimension_key),
    FOREIGN KEY (health_snapshot_id) REFERENCES health_snapshots(id)
);

CREATE TABLE review_queue_items (
    id BINARY(16) PRIMARY KEY,
    item_type VARCHAR(48) NOT NULL,
    title VARCHAR(500) NOT NULL,
    project_code VARCHAR(64),
    severity VARCHAR(24) NOT NULL,
    required_role VARCHAR(48) NOT NULL,
    reason_text VARCHAR(1000) NOT NULL,
    due_at DATETIME(6) NOT NULL
);

CREATE INDEX idx_coverage_project_time ON trace_coverage_snapshots(project_id, calculated_at);
CREATE INDEX idx_scope_project_time ON scope_risk_snapshots(project_id, calculated_at);
CREATE INDEX idx_health_project_time ON health_snapshots(project_id, calculated_at);

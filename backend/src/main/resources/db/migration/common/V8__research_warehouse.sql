-- Durable, same-database research warehouse. Source rows are first copied into
-- load-scoped staging tables; only a completely analysed snapshot is published.
-- Warehouse rows are append-only and never replace authoritative operational data.

CREATE TABLE warehouse_loads (
    id BINARY(16) PRIMARY KEY,
    requested_by BINARY(16) NOT NULL,
    load_status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    source_cutoff_at DATETIME(6) NOT NULL,
    source_sha256 CHAR(64),
    source_count INT NOT NULL DEFAULT 0,
    accepted_count INT NOT NULL DEFAULT 0,
    rejected_count INT NOT NULL DEFAULT 0,
    published_snapshot_id BINARY(16),
    failure_reason VARCHAR(1000),
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (requested_by) REFERENCES user_accounts(id)
);

CREATE TABLE warehouse_load_stages (
    load_id BINARY(16) NOT NULL,
    stage_code VARCHAR(32) NOT NULL,
    stage_order INT NOT NULL,
    stage_status VARCHAR(32) NOT NULL,
    input_count INT NOT NULL DEFAULT 0,
    output_count INT NOT NULL DEFAULT 0,
    details_json JSON NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    PRIMARY KEY (load_id, stage_code),
    UNIQUE (load_id, stage_order),
    FOREIGN KEY (load_id) REFERENCES warehouse_loads(id)
);

CREATE TABLE warehouse_quality_issues (
    id BINARY(16) PRIMARY KEY,
    load_id BINARY(16) NOT NULL,
    study_id BINARY(16),
    issue_code VARCHAR(80) NOT NULL,
    severity VARCHAR(24) NOT NULL,
    field_name VARCHAR(80),
    issue_message VARCHAR(1000) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (load_id) REFERENCES warehouse_loads(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE warehouse_staged_studies (
    load_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    department_id BINARY(16),
    department_code VARCHAR(32),
    department_name VARCHAR(160),
    institutional_code VARCHAR(80),
    doi VARCHAR(255),
    repository_identifier VARCHAR(255),
    program_name VARCHAR(180),
    title VARCHAR(600) NOT NULL,
    normalized_title VARCHAR(600),
    abstract_text TEXT,
    problem_statement TEXT,
    methodology TEXT,
    features_text TEXT,
    data_sources_text TEXT,
    technology_text TEXT,
    intended_users_text TEXT,
    stakeholders_text TEXT,
    site_context TEXT,
    keywords_text TEXT,
    results_text TEXT,
    academic_year VARCHAR(16),
    source_completion_year INT,
    completion_year INT,
    lifecycle_status VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    published_at DATETIME(6),
    archived_at DATETIME(6),
    source_row_version BIGINT NOT NULL,
    source_created_at DATETIME(6) NOT NULL,
    valid_record BOOLEAN NOT NULL DEFAULT TRUE,
    objective_count INT NOT NULL DEFAULT 0,
    topic_count INT NOT NULL DEFAULT 0,
    continuation_count INT NOT NULL DEFAULT 0,
    retrieval_count INT NOT NULL DEFAULT 0,
    transformed_sha256 CHAR(64),
    PRIMARY KEY (load_id, study_id),
    FOREIGN KEY (load_id) REFERENCES warehouse_loads(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE warehouse_staged_objectives (
    load_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    objective_id BINARY(16) NOT NULL,
    objective_order INT NOT NULL,
    statement_text TEXT NOT NULL,
    normalized_statement TEXT,
    PRIMARY KEY (load_id, study_id, objective_id),
    UNIQUE (load_id, study_id, objective_order),
    FOREIGN KEY (load_id, study_id) REFERENCES warehouse_staged_studies(load_id, study_id)
);

CREATE TABLE warehouse_staged_metadata_versions (
    load_id BINARY(16) NOT NULL,
    metadata_version_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    provenance_type VARCHAR(40) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    metadata_json JSON NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (load_id, metadata_version_id),
    UNIQUE (load_id, study_id, version_number),
    FOREIGN KEY (load_id, study_id) REFERENCES warehouse_staged_studies(load_id, study_id)
);

CREATE TABLE warehouse_staged_topics (
    load_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    term_id BINARY(16) NOT NULL,
    term_type VARCHAR(48) NOT NULL,
    canonical_label VARCHAR(160) NOT NULL,
    normalized_label VARCHAR(160),
    active BOOLEAN NOT NULL,
    PRIMARY KEY (load_id, study_id, term_id),
    FOREIGN KEY (load_id, study_id) REFERENCES warehouse_staged_studies(load_id, study_id)
);

CREATE TABLE warehouse_staged_retrievals (
    load_id BINARY(16) NOT NULL,
    discovery_run_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    candidate_rank INT NOT NULL,
    problem_score DECIMAL(5,2) NOT NULL,
    objective_score DECIMAL(5,2) NOT NULL,
    solution_score DECIMAL(5,2) NOT NULL,
    confidence_score DECIMAL(5,2) NOT NULL,
    similarity_band VARCHAR(32) NOT NULL,
    exact_match BOOLEAN NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    run_started_at DATETIME(6) NOT NULL,
    run_completed_at DATETIME(6),
    PRIMARY KEY (load_id, discovery_run_id, study_id),
    FOREIGN KEY (load_id, study_id) REFERENCES warehouse_staged_studies(load_id, study_id)
);

CREATE TABLE warehouse_staged_continuity (
    load_id BINARY(16) NOT NULL,
    fact_key CHAR(64) NOT NULL,
    source_kind VARCHAR(48) NOT NULL,
    source_study_id BINARY(16),
    target_study_id BINARY(16),
    successor_project_id BINARY(16),
    continuation_item_id BINARY(16),
    relationship_type VARCHAR(48) NOT NULL,
    evidence_status VARCHAR(32),
    rationale_text TEXT,
    evidence_at DATETIME(6),
    PRIMARY KEY (load_id, fact_key),
    FOREIGN KEY (load_id) REFERENCES warehouse_loads(id),
    FOREIGN KEY (source_study_id) REFERENCES studies(id),
    FOREIGN KEY (target_study_id) REFERENCES studies(id),
    FOREIGN KEY (successor_project_id) REFERENCES projects(id),
    FOREIGN KEY (continuation_item_id) REFERENCES continuation_items(id)
);

CREATE TABLE warehouse_snapshots (
    id BINARY(16) PRIMARY KEY,
    load_id BINARY(16) NOT NULL UNIQUE,
    snapshot_version INT NOT NULL UNIQUE,
    snapshot_status VARCHAR(32) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    source_cutoff_at DATETIME(6) NOT NULL,
    source_study_count INT NOT NULL,
    accepted_study_count INT NOT NULL,
    rejected_study_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    FOREIGN KEY (load_id) REFERENCES warehouse_loads(id)
);

ALTER TABLE warehouse_loads ADD CONSTRAINT fk_warehouse_load_snapshot
    FOREIGN KEY (published_snapshot_id) REFERENCES warehouse_snapshots(id);

CREATE TABLE dw_department_dimensions (
    snapshot_id BINARY(16) NOT NULL,
    department_id BINARY(16) NOT NULL,
    department_code VARCHAR(32) NOT NULL,
    department_name VARCHAR(160) NOT NULL,
    PRIMARY KEY (snapshot_id, department_id),
    FOREIGN KEY (snapshot_id) REFERENCES warehouse_snapshots(id)
);

CREATE TABLE dw_year_dimensions (
    snapshot_id BINARY(16) NOT NULL,
    completion_year INT NOT NULL,
    PRIMARY KEY (snapshot_id, completion_year),
    FOREIGN KEY (snapshot_id) REFERENCES warehouse_snapshots(id)
);

CREATE TABLE dw_topic_dimensions (
    snapshot_id BINARY(16) NOT NULL,
    term_id BINARY(16) NOT NULL,
    term_type VARCHAR(48) NOT NULL,
    canonical_label VARCHAR(160) NOT NULL,
    normalized_label VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (snapshot_id, term_id),
    FOREIGN KEY (snapshot_id) REFERENCES warehouse_snapshots(id)
);

CREATE TABLE dw_study_dimensions (
    snapshot_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    department_id BINARY(16),
    institutional_code VARCHAR(80),
    doi VARCHAR(255),
    repository_identifier VARCHAR(255),
    program_name VARCHAR(180),
    title VARCHAR(600) NOT NULL,
    normalized_title VARCHAR(600) NOT NULL,
    abstract_text TEXT,
    problem_statement TEXT,
    methodology TEXT,
    features_text TEXT,
    data_sources_text TEXT,
    technology_text TEXT,
    intended_users_text TEXT,
    stakeholders_text TEXT,
    site_context TEXT,
    keywords_text TEXT,
    results_text TEXT,
    academic_year VARCHAR(16),
    source_completion_year INT,
    completion_year INT,
    lifecycle_status VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    published_at DATETIME(6),
    archived_at DATETIME(6),
    source_row_version BIGINT NOT NULL,
    source_created_at DATETIME(6) NOT NULL,
    snapshot_row_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (snapshot_id, study_id),
    FOREIGN KEY (snapshot_id) REFERENCES warehouse_snapshots(id),
    FOREIGN KEY (snapshot_id, department_id) REFERENCES dw_department_dimensions(snapshot_id, department_id),
    FOREIGN KEY (snapshot_id, completion_year) REFERENCES dw_year_dimensions(snapshot_id, completion_year)
);

CREATE TABLE dw_study_objective_facts (
    snapshot_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    objective_id BINARY(16) NOT NULL,
    objective_order INT NOT NULL,
    statement_text TEXT NOT NULL,
    normalized_statement TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, study_id, objective_id),
    UNIQUE (snapshot_id, study_id, objective_order),
    FOREIGN KEY (snapshot_id, study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id)
);

CREATE TABLE dw_study_version_dimensions (
    snapshot_id BINARY(16) NOT NULL,
    metadata_version_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    provenance_type VARCHAR(40) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    metadata_json JSON NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, metadata_version_id),
    UNIQUE (snapshot_id, study_id, version_number),
    FOREIGN KEY (snapshot_id, study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id)
);

CREATE TABLE dw_study_topic_bridge (
    snapshot_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    term_id BINARY(16) NOT NULL,
    PRIMARY KEY (snapshot_id, study_id, term_id),
    FOREIGN KEY (snapshot_id, study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id),
    FOREIGN KEY (snapshot_id, term_id) REFERENCES dw_topic_dimensions(snapshot_id, term_id)
);

CREATE TABLE dw_study_facts (
    snapshot_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    objective_count INT NOT NULL,
    topic_count INT NOT NULL,
    continuation_count INT NOT NULL,
    retrieval_count INT NOT NULL,
    PRIMARY KEY (snapshot_id, study_id),
    FOREIGN KEY (snapshot_id, study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id)
);

CREATE TABLE dw_retrieval_facts (
    snapshot_id BINARY(16) NOT NULL,
    discovery_run_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    candidate_rank INT NOT NULL,
    problem_score DECIMAL(5,2) NOT NULL,
    objective_score DECIMAL(5,2) NOT NULL,
    solution_score DECIMAL(5,2) NOT NULL,
    confidence_score DECIMAL(5,2) NOT NULL,
    similarity_band VARCHAR(32) NOT NULL,
    exact_match BOOLEAN NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    run_started_at DATETIME(6) NOT NULL,
    run_completed_at DATETIME(6),
    PRIMARY KEY (snapshot_id, discovery_run_id, study_id),
    FOREIGN KEY (snapshot_id, study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id)
);

CREATE TABLE dw_continuation_facts (
    snapshot_id BINARY(16) NOT NULL,
    fact_key CHAR(64) NOT NULL,
    source_kind VARCHAR(48) NOT NULL,
    source_study_id BINARY(16),
    target_study_id BINARY(16),
    successor_project_id BINARY(16),
    continuation_item_id BINARY(16),
    relationship_type VARCHAR(48) NOT NULL,
    evidence_status VARCHAR(32),
    rationale_text TEXT,
    evidence_at DATETIME(6),
    PRIMARY KEY (snapshot_id, fact_key),
    FOREIGN KEY (snapshot_id) REFERENCES warehouse_snapshots(id),
    FOREIGN KEY (snapshot_id, source_study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id),
    FOREIGN KEY (snapshot_id, target_study_id) REFERENCES dw_study_dimensions(snapshot_id, study_id)
);

CREATE INDEX idx_warehouse_load_status ON warehouse_loads(load_status, started_at);
CREATE INDEX idx_warehouse_stage_status ON warehouse_load_stages(load_id, stage_order, stage_status);
CREATE INDEX idx_warehouse_quality_load ON warehouse_quality_issues(load_id, severity, issue_code);
CREATE INDEX idx_warehouse_snapshot_status ON warehouse_snapshots(snapshot_status, published_at);
CREATE INDEX idx_warehouse_snapshot_source ON warehouse_snapshots(source_sha256, snapshot_status);
CREATE INDEX idx_dw_study_year ON dw_study_dimensions(snapshot_id, completion_year, visibility);
CREATE INDEX idx_dw_study_department ON dw_study_dimensions(snapshot_id, department_id, visibility);
CREATE INDEX idx_dw_topic_label ON dw_topic_dimensions(snapshot_id, term_type, normalized_label);
CREATE INDEX idx_dw_continuation_source ON dw_continuation_facts(snapshot_id, source_study_id, target_study_id);

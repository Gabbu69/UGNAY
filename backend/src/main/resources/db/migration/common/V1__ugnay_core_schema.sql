CREATE TABLE departments (
    id BINARY(16) PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE user_accounts (
    id BINARY(16) PRIMARY KEY,
    department_id BINARY(16),
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (department_id) REFERENCES departments(id)
);

CREATE TABLE password_credentials (
    user_id BINARY(16) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at DATETIME(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_accounts(id)
);

CREATE TABLE roles (
    id BINARY(16) PRIMARY KEY,
    code VARCHAR(48) NOT NULL UNIQUE,
    label VARCHAR(96) NOT NULL
);

CREATE TABLE user_roles (
    user_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES user_accounts(id),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

CREATE TABLE invitations (
    id BINARY(16) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    intended_role VARCHAR(48) NOT NULL,
    invited_by BINARY(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (invited_by) REFERENCES user_accounts(id)
);

CREATE TABLE studies (
    id BINARY(16) PRIMARY KEY,
    department_id BINARY(16),
    source_project_id BINARY(16),
    institutional_code VARCHAR(80),
    doi VARCHAR(255),
    title VARCHAR(600) NOT NULL,
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
    academic_year VARCHAR(16),
    lifecycle_status VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    repository_identifier VARCHAR(255),
    published_at DATETIME(6),
    archived_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (institutional_code),
    UNIQUE (doi),
    UNIQUE (source_project_id),
    FOREIGN KEY (department_id) REFERENCES departments(id)
);

CREATE TABLE authors (
    id BINARY(16) PRIMARY KEY,
    display_name VARCHAR(180) NOT NULL,
    institutional_identifier VARCHAR(80)
);

CREATE TABLE study_authors (
    study_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    author_order INT NOT NULL,
    PRIMARY KEY (study_id, author_id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (author_id) REFERENCES authors(id)
);

CREATE TABLE study_objectives (
    id BINARY(16) PRIMARY KEY,
    study_id BINARY(16) NOT NULL,
    objective_order INT NOT NULL,
    statement_text TEXT NOT NULL,
    UNIQUE (study_id, objective_order),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE taxonomy_terms (
    id BINARY(16) PRIMARY KEY,
    term_type VARCHAR(48) NOT NULL,
    canonical_label VARCHAR(160) NOT NULL,
    filipino_label VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (term_type, canonical_label)
);

CREATE TABLE study_terms (
    study_id BINARY(16) NOT NULL,
    term_id BINARY(16) NOT NULL,
    PRIMARY KEY (study_id, term_id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (term_id) REFERENCES taxonomy_terms(id)
);

CREATE TABLE continuation_items (
    id BINARY(16) PRIMARY KEY,
    study_id BINARY(16) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL,
    item_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE study_relationships (
    id BINARY(16) PRIMARY KEY,
    source_study_id BINARY(16) NOT NULL,
    target_study_id BINARY(16) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (source_study_id, target_study_id, relationship_type),
    FOREIGN KEY (source_study_id) REFERENCES studies(id),
    FOREIGN KEY (target_study_id) REFERENCES studies(id)
);

CREATE TABLE documents (
    id BINARY(16) PRIMARY KEY,
    owner_type VARCHAR(48) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    document_purpose VARCHAR(48) NOT NULL,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE document_versions (
    id BINARY(16) PRIMARY KEY,
    document_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    object_key VARCHAR(700) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    uploaded_by BINARY(16) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    scan_status VARCHAR(32) NOT NULL,
    extraction_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (document_id, version_number),
    FOREIGN KEY (document_id) REFERENCES documents(id),
    FOREIGN KEY (uploaded_by) REFERENCES user_accounts(id)
);

CREATE TABLE import_batches (
    id BINARY(16) PRIMARY KEY,
    imported_by BINARY(16) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    import_status VARCHAR(32) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (imported_by) REFERENCES user_accounts(id)
);

CREATE TABLE extraction_runs (
    id BINARY(16) PRIMARY KEY,
    document_version_id BINARY(16) NOT NULL,
    extractor_version VARCHAR(80) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    extracted_character_count INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000),
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (document_version_id) REFERENCES document_versions(id)
);

CREATE TABLE document_segments (
    id BINARY(16) PRIMARY KEY,
    extraction_run_id BINARY(16) NOT NULL,
    segment_order INT NOT NULL,
    page_number INT,
    section_label VARCHAR(160),
    extracted_text TEXT NOT NULL,
    UNIQUE (extraction_run_id, segment_order),
    FOREIGN KEY (extraction_run_id) REFERENCES extraction_runs(id)
);

CREATE TABLE model_versions (
    id BINARY(16) PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL,
    model_sha256 CHAR(64) NOT NULL,
    dimensions INT NOT NULL,
    tokenizer_sha256 CHAR(64),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (model_name, model_sha256)
);

CREATE TABLE study_embeddings (
    study_id BINARY(16) NOT NULL,
    model_version_id BINARY(16) NOT NULL,
    field_name VARCHAR(48) NOT NULL,
    vector_bytes BLOB NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (study_id, model_version_id, field_name),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (model_version_id) REFERENCES model_versions(id)
);

CREATE TABLE problem_cases (
    id BINARY(16) PRIMARY KEY,
    department_id BINARY(16) NOT NULL,
    created_by BINARY(16) NOT NULL,
    title VARCHAR(400) NOT NULL,
    problem_statement TEXT NOT NULL,
    stakeholder VARCHAR(240) NOT NULL,
    affected_users TEXT NOT NULL,
    site_context TEXT NOT NULL,
    desired_outcome TEXT NOT NULL,
    constraints_text TEXT,
    privacy_classification VARCHAR(32) NOT NULL,
    intake_status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (department_id) REFERENCES departments(id),
    FOREIGN KEY (created_by) REFERENCES user_accounts(id)
);

CREATE TABLE problem_evidence (
    id BINARY(16) PRIMARY KEY,
    problem_case_id BINARY(16) NOT NULL,
    evidence_type VARCHAR(48) NOT NULL,
    summary TEXT NOT NULL,
    document_id BINARY(16),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (problem_case_id) REFERENCES problem_cases(id),
    FOREIGN KEY (document_id) REFERENCES documents(id)
);

CREATE TABLE proposals (
    id BINARY(16) PRIMARY KEY,
    problem_case_id BINARY(16) NOT NULL,
    submitted_by BINARY(16) NOT NULL,
    proposed_title VARCHAR(500) NOT NULL,
    proposed_solution TEXT NOT NULL,
    methodology TEXT,
    technology_text TEXT,
    data_sources_text TEXT,
    intended_users_text TEXT,
    proposal_status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    submitted_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (problem_case_id) REFERENCES problem_cases(id),
    FOREIGN KEY (submitted_by) REFERENCES user_accounts(id)
);

CREATE TABLE proposal_objectives (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    objective_order INT NOT NULL,
    statement_text TEXT NOT NULL,
    novelty_rationale TEXT,
    baseline_measure VARCHAR(300),
    target_measure VARCHAR(300),
    evaluation_method VARCHAR(300),
    UNIQUE (proposal_id, objective_order),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id)
);

CREATE TABLE algorithm_configurations (
    id BINARY(16) PRIMARY KEY,
    algorithm_version VARCHAR(80) NOT NULL UNIQUE,
    configuration_json JSON NOT NULL,
    configuration_sha256 CHAR(64) NOT NULL,
    activated_at DATETIME(6) NOT NULL,
    retired_at DATETIME(6)
);

CREATE TABLE discovery_runs (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    algorithm_configuration_id BINARY(16) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    assessment_status VARCHAR(32) NOT NULL,
    recommendation VARCHAR(40) NOT NULL,
    confidence_score DECIMAL(5,2) NOT NULL,
    explanation TEXT NOT NULL,
    input_snapshot_json JSON NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (algorithm_configuration_id) REFERENCES algorithm_configurations(id)
);

CREATE TABLE discovery_candidates (
    id BINARY(16) PRIMARY KEY,
    discovery_run_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    candidate_rank INT NOT NULL,
    problem_score DECIMAL(5,2) NOT NULL,
    objective_score DECIMAL(5,2) NOT NULL,
    solution_score DECIMAL(5,2) NOT NULL,
    confidence_score DECIMAL(5,2) NOT NULL,
    similarity_band VARCHAR(32) NOT NULL,
    exact_match BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (discovery_run_id, study_id),
    UNIQUE (discovery_run_id, candidate_rank),
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE candidate_evidence (
    id BINARY(16) PRIMARY KEY,
    discovery_candidate_id BINARY(16) NOT NULL,
    field_name VARCHAR(48) NOT NULL,
    component_type VARCHAR(32) NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    matched_excerpt TEXT,
    explanation VARCHAR(1000) NOT NULL,
    FOREIGN KEY (discovery_candidate_id) REFERENCES discovery_candidates(id)
);

CREATE TABLE proposal_decisions (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    discovery_run_id BINARY(16),
    disposition VARCHAR(48) NOT NULL,
    rationale TEXT NOT NULL,
    decided_by BINARY(16) NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    UNIQUE (proposal_id),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id),
    FOREIGN KEY (decided_by) REFERENCES user_accounts(id)
);

CREATE TABLE decision_target_studies (
    decision_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    primary_target BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (decision_id, study_id),
    FOREIGN KEY (decision_id) REFERENCES proposal_decisions(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE projects (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL UNIQUE,
    department_id BINARY(16) NOT NULL,
    project_code VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(500) NOT NULL,
    project_status VARCHAR(32) NOT NULL,
    route_type VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (department_id) REFERENCES departments(id)
);

ALTER TABLE studies ADD CONSTRAINT fk_study_source_project FOREIGN KEY (source_project_id) REFERENCES projects(id);

CREATE TABLE project_memberships (
    project_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    membership_role VARCHAR(48) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (project_id, user_id, membership_role),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (user_id) REFERENCES user_accounts(id)
);

CREATE TABLE project_predecessors (
    project_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    lineage_type VARCHAR(32) NOT NULL,
    primary_predecessor BOOLEAN NOT NULL DEFAULT FALSE,
    rationale TEXT NOT NULL,
    PRIMARY KEY (project_id, study_id),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE project_baselines (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_number INT NOT NULL,
    baseline_status VARCHAR(32) NOT NULL,
    approved_by BINARY(16),
    approval_rationale TEXT,
    approved_at DATETIME(6),
    content_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (project_id, baseline_number),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (approved_by) REFERENCES user_accounts(id)
);

CREATE TABLE trace_items (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL,
    current_revision INT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (project_id, item_key),
    UNIQUE (project_id, id),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE trace_item_revisions (
    id BINARY(16) PRIMARY KEY,
    trace_item_id BINARY(16) NOT NULL,
    revision_number INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    priority_code VARCHAR(16),
    acceptance_criteria TEXT,
    verification_method VARCHAR(300),
    detail_json JSON,
    revision_status VARCHAR(32) NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (trace_item_id, revision_number),
    FOREIGN KEY (trace_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (created_by) REFERENCES user_accounts(id)
);

CREATE TABLE baseline_items (
    baseline_id BINARY(16) NOT NULL,
    trace_item_id BINARY(16) NOT NULL,
    trace_item_revision_id BINARY(16) NOT NULL,
    PRIMARY KEY (baseline_id, trace_item_id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (trace_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (trace_item_revision_id) REFERENCES trace_item_revisions(id)
);

CREATE TABLE trace_links (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    target_item_id BINARY(16) NOT NULL,
    link_type VARCHAR(48) NOT NULL,
    link_status VARCHAR(32) NOT NULL,
    rationale TEXT,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (project_id, source_item_id, target_item_id, link_type),
    FOREIGN KEY (project_id, source_item_id) REFERENCES trace_items(project_id, id),
    FOREIGN KEY (project_id, target_item_id) REFERENCES trace_items(project_id, id)
);

CREATE TABLE baseline_links (
    baseline_id BINARY(16) NOT NULL,
    trace_link_id BINARY(16) NOT NULL,
    PRIMARY KEY (baseline_id, trace_link_id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (trace_link_id) REFERENCES trace_links(id)
);

CREATE TABLE change_requests (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    based_on_baseline_id BINARY(16) NOT NULL,
    title VARCHAR(400) NOT NULL,
    rationale TEXT NOT NULL,
    request_status VARCHAR(32) NOT NULL,
    boundary_flags_json JSON,
    requested_by BINARY(16) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (based_on_baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (requested_by) REFERENCES user_accounts(id)
);

CREATE TABLE change_request_items (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL,
    trace_item_id BINARY(16),
    operation_type VARCHAR(32) NOT NULL,
    proposed_revision_json JSON NOT NULL,
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (trace_item_id) REFERENCES trace_items(id)
);

CREATE TABLE impact_paths (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL,
    source_item_id BINARY(16) NOT NULL,
    impacted_item_id BINARY(16) NOT NULL,
    path_json JSON NOT NULL,
    hop_count INT NOT NULL,
    severity VARCHAR(24) NOT NULL,
    evidence_becomes_stale BOOLEAN NOT NULL DEFAULT FALSE,
    calculated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (source_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (impacted_item_id) REFERENCES trace_items(id)
);

CREATE TABLE change_decisions (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL UNIQUE,
    disposition VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    decided_by BINARY(16) NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    resulting_baseline_id BINARY(16),
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (decided_by) REFERENCES user_accounts(id),
    FOREIGN KEY (resulting_baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE test_executions (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    test_item_id BINARY(16) NOT NULL,
    baseline_id BINARY(16) NOT NULL,
    build_identifier VARCHAR(160) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    evidence_document_id BINARY(16),
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    executed_by BINARY(16) NOT NULL,
    executed_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (test_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (evidence_document_id) REFERENCES documents(id),
    FOREIGN KEY (executed_by) REFERENCES user_accounts(id)
);

CREATE TABLE repository_references (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    repository_url VARCHAR(700) NOT NULL,
    commit_hash VARCHAR(80) NOT NULL,
    release_tag VARCHAR(160),
    licence_name VARCHAR(160),
    access_status VARCHAR(32) NOT NULL,
    setup_instructions TEXT NOT NULL,
    verified_at DATETIME(6),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE release_snapshots (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_id BINARY(16) NOT NULL,
    repository_reference_id BINARY(16),
    build_identifier VARCHAR(160) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id),
    FOREIGN KEY (repository_reference_id) REFERENCES repository_references(id)
);

CREATE TABLE completion_packages (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL UNIQUE,
    package_status VARCHAR(32) NOT NULL,
    readiness_score DECIMAL(5,2) NOT NULL,
    code_data_rights_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    ownership_notes TEXT,
    contact_path VARCHAR(500),
    completed_by BINARY(16),
    completed_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (completed_by) REFERENCES user_accounts(id)
);

CREATE TABLE completion_package_items (
    id BINARY(16) PRIMARY KEY,
    completion_package_id BINARY(16) NOT NULL,
    item_type VARCHAR(48) NOT NULL,
    title VARCHAR(300) NOT NULL,
    item_status VARCHAR(32) NOT NULL,
    document_id BINARY(16),
    notes TEXT,
    FOREIGN KEY (completion_package_id) REFERENCES completion_packages(id),
    FOREIGN KEY (document_id) REFERENCES documents(id)
);

CREATE TABLE continuation_item_claims (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    continuation_item_id BINARY(16) NOT NULL,
    claimed_by BINARY(16) NOT NULL,
    claim_rationale TEXT NOT NULL,
    claim_status VARCHAR(32) NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    UNIQUE (project_id, continuation_item_id),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (continuation_item_id) REFERENCES continuation_items(id),
    FOREIGN KEY (claimed_by) REFERENCES user_accounts(id)
);

CREATE TABLE continuation_claim_outcomes (
    id BINARY(16) PRIMARY KEY,
    claim_id BINARY(16) NOT NULL UNIQUE,
    outcome_status VARCHAR(32) NOT NULL,
    outcome_summary TEXT NOT NULL,
    evidence_document_id BINARY(16),
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (claim_id) REFERENCES continuation_item_claims(id),
    FOREIGN KEY (evidence_document_id) REFERENCES documents(id)
);

CREATE TABLE analysis_runs (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_id BINARY(16),
    analysis_type VARCHAR(48) NOT NULL,
    assessment_status VARCHAR(32) NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    configuration_json JSON NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE findings (
    id BINARY(16) PRIMARY KEY,
    analysis_run_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    finding_code VARCHAR(80) NOT NULL,
    severity VARCHAR(24) NOT NULL,
    finding_state VARCHAR(24) NOT NULL,
    title VARCHAR(300) NOT NULL,
    explanation TEXT NOT NULL,
    next_action TEXT NOT NULL,
    accepted_by BINARY(16),
    acceptance_rationale TEXT,
    acceptance_expires_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs(id),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (accepted_by) REFERENCES user_accounts(id)
);

CREATE TABLE finding_evidence (
    id BINARY(16) PRIMARY KEY,
    finding_id BINARY(16) NOT NULL,
    trace_item_id BINARY(16),
    trace_link_id BINARY(16),
    evidence_label VARCHAR(300) NOT NULL,
    evidence_snapshot_json JSON,
    FOREIGN KEY (finding_id) REFERENCES findings(id),
    FOREIGN KEY (trace_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (trace_link_id) REFERENCES trace_links(id)
);

CREATE TABLE health_snapshots (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    baseline_id BINARY(16),
    alignment_score DECIMAL(5,2),
    requirement_readiness_score DECIMAL(5,2),
    verification_score DECIMAL(5,2),
    scope_stability_score DECIMAL(5,2),
    continuity_readiness_score DECIMAL(5,2),
    overall_status VARCHAR(32) NOT NULL,
    calculation_json JSON NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE analysis_jobs (
    id BINARY(16) PRIMARY KEY,
    job_type VARCHAR(48) NOT NULL,
    subject_type VARCHAR(48) NOT NULL,
    subject_id BINARY(16) NOT NULL,
    job_status VARCHAR(32) NOT NULL,
    progress_percent INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6)
);

CREATE TABLE audit_events (
    id BINARY(16) PRIMARY KEY,
    actor_id BINARY(16),
    action_code VARCHAR(80) NOT NULL,
    subject_type VARCHAR(48) NOT NULL,
    subject_id BINARY(16),
    event_summary VARCHAR(500) NOT NULL,
    event_snapshot_json JSON,
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY (actor_id) REFERENCES user_accounts(id)
);

CREATE INDEX idx_studies_status_year ON studies(lifecycle_status, academic_year);
CREATE INDEX idx_continuation_study_status ON continuation_items(study_id, item_status);
CREATE INDEX idx_problem_creator_status ON problem_cases(created_by, intake_status);
CREATE INDEX idx_discovery_proposal_time ON discovery_runs(proposal_id, started_at);
CREATE INDEX idx_candidate_scores ON discovery_candidates(discovery_run_id, problem_score, solution_score);
CREATE INDEX idx_project_status ON projects(project_status, department_id);
CREATE INDEX idx_trace_project_type ON trace_items(project_id, item_type, lifecycle_status);
CREATE INDEX idx_trace_link_project_source ON trace_links(project_id, source_item_id);
CREATE INDEX idx_trace_link_project_target ON trace_links(project_id, target_item_id);
CREATE INDEX idx_findings_project_state ON findings(project_id, finding_state, severity);
CREATE INDEX idx_audit_time ON audit_events(occurred_at);

-- Spring Session is Flyway-owned in every environment so MySQL does not rely
-- on embedded-only initializer behavior.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

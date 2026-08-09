ALTER TABLE studies ADD COLUMN program_name VARCHAR(180);

CREATE TABLE proposal_continuation_evidence (
    proposal_id BINARY(16) PRIMARY KEY,
    predecessor_study_id BINARY(16) NOT NULL,
    code_access_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    data_access_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    access_notes TEXT NOT NULL,
    recorded_by BINARY(16) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (predecessor_study_id) REFERENCES studies(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE TABLE proposal_objective_continuation_links (
    proposal_id BINARY(16) NOT NULL,
    proposal_objective_id BINARY(16) NOT NULL,
    continuation_item_id BINARY(16) NOT NULL,
    rationale TEXT NOT NULL,
    PRIMARY KEY (proposal_id, proposal_objective_id, continuation_item_id),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (proposal_objective_id) REFERENCES proposal_objectives(id),
    FOREIGN KEY (continuation_item_id) REFERENCES continuation_items(id)
);

CREATE TABLE proposal_improvement_claims (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    predecessor_study_id BINARY(16) NOT NULL,
    continuation_item_id BINARY(16) NOT NULL,
    claim_text TEXT NOT NULL,
    baseline_measure VARCHAR(500) NOT NULL,
    target_measure VARCHAR(500) NOT NULL,
    evaluation_method TEXT NOT NULL,
    recorded_by BINARY(16) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (predecessor_study_id) REFERENCES studies(id),
    FOREIGN KEY (continuation_item_id) REFERENCES continuation_items(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE TABLE adviser_recommendations (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    discovery_run_id BINARY(16) NOT NULL,
    recommendation VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    adviser_id BINARY(16) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id),
    FOREIGN KEY (adviser_id) REFERENCES user_accounts(id)
);

CREATE TABLE finding_actions (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    finding_fingerprint CHAR(64) NOT NULL,
    finding_code VARCHAR(80) NOT NULL,
    action_state VARCHAR(24) NOT NULL,
    rationale TEXT NOT NULL,
    expires_at DATETIME(6),
    acted_by BINARY(16) NOT NULL,
    acted_at DATETIME(6) NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (acted_by) REFERENCES user_accounts(id)
);

CREATE INDEX idx_finding_action_latest ON finding_actions(project_id, finding_fingerprint, acted_at);

CREATE TABLE change_operations (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL,
    operation_order INT NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    target_item_id BINARY(16),
    item_type VARCHAR(32),
    item_key VARCHAR(64),
    title VARCHAR(500),
    description TEXT,
    priority VARCHAR(32),
    acceptance_criteria TEXT,
    verification_method VARCHAR(500),
    source_item_id BINARY(16),
    link_target_item_id BINARY(16),
    relationship_type VARCHAR(48),
    remove_relationship BOOLEAN NOT NULL DEFAULT FALSE,
    rationale TEXT NOT NULL,
    UNIQUE (change_request_id, operation_order),
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (target_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (source_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (link_target_item_id) REFERENCES trace_items(id)
);

CREATE TABLE change_decision_history (
    id BINARY(16) PRIMARY KEY,
    change_request_id BINARY(16) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    decided_by BINARY(16) NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    resulting_baseline_id BINARY(16),
    FOREIGN KEY (change_request_id) REFERENCES change_requests(id),
    FOREIGN KEY (decided_by) REFERENCES user_accounts(id),
    FOREIGN KEY (resulting_baseline_id) REFERENCES project_baselines(id)
);

CREATE TABLE continuation_claim_objectives (
    claim_id BINARY(16) PRIMARY KEY,
    successor_objective_id BINARY(16) NOT NULL,
    FOREIGN KEY (claim_id) REFERENCES continuation_item_claims(id),
    FOREIGN KEY (successor_objective_id) REFERENCES trace_items(id)
);

CREATE TABLE continuation_claim_events (
    id BINARY(16) PRIMARY KEY,
    claim_id BINARY(16) NOT NULL,
    outcome_status VARCHAR(32) NOT NULL,
    outcome_summary TEXT NOT NULL,
    evidence_document_id BINARY(16),
    evidence_trace_item_id BINARY(16),
    recorded_by BINARY(16) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (claim_id) REFERENCES continuation_item_claims(id),
    FOREIGN KEY (evidence_document_id) REFERENCES documents(id),
    FOREIGN KEY (evidence_trace_item_id) REFERENCES trace_items(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE TABLE study_document_publications (
    study_id BINARY(16) PRIMARY KEY,
    document_version_id BINARY(16) NOT NULL UNIQUE,
    published_by BINARY(16) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (document_version_id) REFERENCES document_versions(id),
    FOREIGN KEY (published_by) REFERENCES user_accounts(id)
);

CREATE TABLE discovery_candidate_assessments (
    id BINARY(16) PRIMARY KEY,
    discovery_run_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    assessment VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    reviewed_by BINARY(16) NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (reviewed_by) REFERENCES user_accounts(id)
);

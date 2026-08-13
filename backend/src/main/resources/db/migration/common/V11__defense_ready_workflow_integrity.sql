-- Defense-ready workflow integrity.  Intake retries, evidence references,
-- change preview provenance, and review ownership are durable domain data.

CREATE TABLE intake_submissions (
    id BINARY(16) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    problem_case_id BINARY(16) NOT NULL,
    proposal_id BINARY(16) NOT NULL,
    discovery_run_id BINARY(16) NOT NULL,
    submitted_by BINARY(16) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    UNIQUE (submitted_by, idempotency_key),
    FOREIGN KEY (problem_case_id) REFERENCES problem_cases(id),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (discovery_run_id) REFERENCES discovery_runs(id),
    FOREIGN KEY (submitted_by) REFERENCES user_accounts(id)
);

CREATE INDEX idx_intake_submission_time ON intake_submissions(submitted_by, submitted_at);

CREATE TABLE evidence_references (
    id BINARY(16) PRIMARY KEY,
    subject_type VARCHAR(48) NOT NULL,
    subject_id BINARY(16) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_label VARCHAR(300) NOT NULL,
    reference_location VARCHAR(1000),
    document_id BINARY(16),
    content_sha256 CHAR(64),
    verification_state VARCHAR(32) NOT NULL,
    recorded_by BINARY(16) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE INDEX idx_evidence_reference_subject ON evidence_references(subject_type, subject_id, captured_at);

ALTER TABLE change_requests ADD COLUMN operation_set_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE impact_previews ADD COLUMN operation_set_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE impact_previews ADD COLUMN operation_set_sha256 CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE review_queue_items ADD COLUMN project_id BINARY(16);
UPDATE review_queue_items
SET project_id = (SELECT projects.id FROM projects WHERE projects.project_code = review_queue_items.project_code)
WHERE project_code IS NOT NULL;
ALTER TABLE review_queue_items ADD CONSTRAINT fk_review_queue_project FOREIGN KEY (project_id) REFERENCES projects(id);
CREATE INDEX idx_review_queue_project_due ON review_queue_items(project_id, due_at);

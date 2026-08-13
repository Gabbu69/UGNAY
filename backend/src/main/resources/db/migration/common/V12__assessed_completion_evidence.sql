-- Server-derived continuity readiness and append-only independent verification.
-- Legacy ratios are retained as PARTIAL self-assessments; new workflow values
-- are recalculated from persisted traces and evidence references.

ALTER TABLE completion_packages ADD COLUMN readiness_state VARCHAR(32) NOT NULL DEFAULT 'UNASSESSED';
ALTER TABLE completion_criteria ADD COLUMN assessment_state VARCHAR(32) NOT NULL DEFAULT 'UNASSESSED';
ALTER TABLE completion_criteria ADD COLUMN evidence_source VARCHAR(160);
ALTER TABLE completion_criteria ADD COLUMN assessed_at DATETIME(6);

UPDATE completion_packages SET readiness_state='PARTIAL' WHERE readiness_score > 0;
UPDATE completion_criteria
SET assessment_state='PARTIAL', evidence_source='LEGACY_SELF_ASSESSMENT', assessed_at=CURRENT_TIMESTAMP(6)
WHERE completion_ratio > 0;

CREATE TABLE evidence_reference_verifications (
    id BINARY(16) PRIMARY KEY,
    evidence_reference_id BINARY(16) NOT NULL,
    verification_state VARCHAR(32) NOT NULL,
    verification_notes VARCHAR(1000) NOT NULL,
    verified_by BINARY(16) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    FOREIGN KEY (evidence_reference_id) REFERENCES evidence_references(id),
    FOREIGN KEY (verified_by) REFERENCES user_accounts(id)
);

CREATE INDEX idx_evidence_verification_reference
    ON evidence_reference_verifications(evidence_reference_id, verified_at);

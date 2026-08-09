-- Durable state required to move PDF text extraction out of the upload request.
-- V1 already owns documents, versions, runs, and normalized text segments.

ALTER TABLE document_versions ADD COLUMN storage_etag VARCHAR(160);

ALTER TABLE extraction_runs ADD COLUMN queued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE extraction_runs ADD COLUMN progress_percent INT NOT NULL DEFAULT 0;
ALTER TABLE extraction_runs ADD COLUMN page_count INT NOT NULL DEFAULT 0;
ALTER TABLE extraction_runs ADD COLUMN max_character_count INT NOT NULL DEFAULT 0;
ALTER TABLE extraction_runs ADD COLUMN timeout_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE extraction_runs ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE extraction_runs ADD COLUMN manual_review_required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE extraction_runs ADD COLUMN publication_eligible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_extraction_runs_status_queue ON extraction_runs(run_status, queued_at);

-- Evaluation reports remain private until a coordinator or curator explicitly
-- publishes a terminal run. Legacy corpus rows have no source visibility and
-- therefore fail closed for review/publication until recreated.

ALTER TABLE evaluation_corpus_items
    ADD COLUMN source_visibility VARCHAR(24);

ALTER TABLE evaluation_runs
    ADD COLUMN report_status VARCHAR(24) NOT NULL DEFAULT 'PRIVATE';

ALTER TABLE evaluation_runs
    ADD COLUMN published_by BINARY(16);

ALTER TABLE evaluation_runs
    ADD COLUMN published_at DATETIME(6);

ALTER TABLE evaluation_runs
    ADD CONSTRAINT fk_evaluation_runs_published_by
        FOREIGN KEY (published_by) REFERENCES user_accounts(id);

CREATE INDEX idx_evaluation_reports_published
    ON evaluation_runs(report_status, published_at);

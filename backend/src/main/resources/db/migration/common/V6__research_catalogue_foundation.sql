-- Research catalogue fidelity and reproducible retrieval foundation.
-- Existing operational rows remain authoritative; all new evidence tables are
-- append-only snapshots or rebuildable derived profiles.

ALTER TABLE studies ADD COLUMN results_text TEXT;
ALTER TABLE studies ADD COLUMN completion_year INT;

CREATE TABLE study_metadata_versions (
    id BINARY(16) PRIMARY KEY,
    study_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    provenance_type VARCHAR(40) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    metadata_json JSON NOT NULL,
    recorded_by BINARY(16),
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE (study_id, version_number),
    UNIQUE (study_id, source_sha256),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE TABLE study_search_profiles (
    id BINARY(16) PRIMARY KEY,
    study_id BINARY(16) NOT NULL,
    metadata_version_id BINARY(16),
    normalizer_version VARCHAR(80) NOT NULL,
    title_text TEXT,
    problem_text TEXT,
    objectives_text TEXT,
    methodology_text TEXT,
    keyword_text TEXT,
    combined_text TEXT NOT NULL,
    profile_sha256 CHAR(64) NOT NULL,
    profile_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (study_id, normalizer_version, profile_sha256),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (metadata_version_id) REFERENCES study_metadata_versions(id)
);

CREATE TABLE retrieval_corpus_snapshots (
    id BINARY(16) PRIMARY KEY,
    snapshot_name VARCHAR(160) NOT NULL,
    source_cutoff DATETIME(6) NOT NULL,
    corpus_sha256 CHAR(64) NOT NULL UNIQUE,
    study_count INT NOT NULL,
    snapshot_status VARCHAR(32) NOT NULL,
    created_by BINARY(16),
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (created_by) REFERENCES user_accounts(id)
);

CREATE TABLE retrieval_corpus_items (
    corpus_snapshot_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    profile_id BINARY(16) NOT NULL,
    item_order INT NOT NULL,
    PRIMARY KEY (corpus_snapshot_id, study_id),
    UNIQUE (corpus_snapshot_id, item_order),
    FOREIGN KEY (corpus_snapshot_id) REFERENCES retrieval_corpus_snapshots(id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (profile_id) REFERENCES study_search_profiles(id)
);

CREATE TABLE search_term_statistics (
    corpus_snapshot_id BINARY(16) NOT NULL,
    term_text VARCHAR(255) NOT NULL,
    document_frequency INT NOT NULL,
    inverse_document_frequency DECIMAL(12,8) NOT NULL,
    PRIMARY KEY (corpus_snapshot_id, term_text),
    FOREIGN KEY (corpus_snapshot_id) REFERENCES retrieval_corpus_snapshots(id)
);

ALTER TABLE algorithm_configurations ADD COLUMN algorithm_code VARCHAR(80);
ALTER TABLE algorithm_configurations ADD COLUMN reproducibility_status VARCHAR(32) NOT NULL DEFAULT 'LEGACY_PARTIAL';
ALTER TABLE discovery_runs ADD COLUMN corpus_snapshot_id BINARY(16);
ALTER TABLE discovery_runs ADD COLUMN environment_snapshot_json JSON;
ALTER TABLE discovery_runs ADD COLUMN code_version VARCHAR(80);
ALTER TABLE discovery_runs ADD FOREIGN KEY (corpus_snapshot_id) REFERENCES retrieval_corpus_snapshots(id);

CREATE INDEX idx_study_completion_year ON studies(completion_year);
CREATE INDEX idx_study_metadata_history ON study_metadata_versions(study_id, recorded_at);
CREATE INDEX idx_search_profile_study ON study_search_profiles(study_id, profile_status);

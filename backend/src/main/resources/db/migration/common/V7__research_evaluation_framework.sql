CREATE TABLE evaluation_datasets (
    id BINARY(16) PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    description TEXT,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (created_by) REFERENCES user_accounts(id)
);

CREATE TABLE evaluation_dataset_versions (
    id BINARY(16) PRIMARY KEY,
    dataset_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    dataset_status VARCHAR(32) NOT NULL,
    corpus_sha256 CHAR(64) NOT NULL,
    dataset_sha256 CHAR(64),
    manifest_json JSON NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    frozen_by BINARY(16),
    frozen_at DATETIME(6),
    UNIQUE (dataset_id, version_number),
    FOREIGN KEY (dataset_id) REFERENCES evaluation_datasets(id),
    FOREIGN KEY (created_by) REFERENCES user_accounts(id),
    FOREIGN KEY (frozen_by) REFERENCES user_accounts(id)
);

CREATE TABLE evaluation_corpus_items (
    dataset_version_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    item_order INT NOT NULL,
    study_profile_sha256 CHAR(64) NOT NULL,
    profile_text TEXT NOT NULL,
    study_snapshot_json JSON NOT NULL,
    PRIMARY KEY (dataset_version_id, study_id),
    UNIQUE (dataset_version_id, item_order),
    FOREIGN KEY (dataset_version_id) REFERENCES evaluation_dataset_versions(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE evaluation_queries (
    id BINARY(16) PRIMARY KEY,
    dataset_version_id BINARY(16) NOT NULL,
    external_key VARCHAR(120) NOT NULL,
    query_split VARCHAR(16) NOT NULL,
    query_title VARCHAR(600) NOT NULL,
    query_text TEXT NOT NULL,
    query_snapshot_json JSON NOT NULL,
    query_sha256 CHAR(64) NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE (dataset_version_id, external_key),
    FOREIGN KEY (dataset_version_id) REFERENCES evaluation_dataset_versions(id),
    FOREIGN KEY (created_by) REFERENCES user_accounts(id)
);

-- Reviewer evidence is insert-only. A later correction is a new revision that
-- points at the prior row; frozen dataset versions reject further revisions.
CREATE TABLE evaluation_judgments (
    id BINARY(16) PRIMARY KEY,
    query_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    reviewer_id BINARY(16) NOT NULL,
    revision_number INT NOT NULL,
    relevance_grade INT NOT NULL CHECK (relevance_grade BETWEEN 0 AND 3),
    rationale TEXT NOT NULL,
    supersedes_judgment_id BINARY(16),
    judged_at DATETIME(6) NOT NULL,
    UNIQUE (query_id, study_id, reviewer_id, revision_number),
    FOREIGN KEY (query_id) REFERENCES evaluation_queries(id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (reviewer_id) REFERENCES user_accounts(id),
    FOREIGN KEY (supersedes_judgment_id) REFERENCES evaluation_judgments(id)
);

-- Qrels are separately adjudicated evidence. They are not algorithm output and
-- never alter proposal routes or academic decisions.
CREATE TABLE evaluation_qrels (
    id BINARY(16) PRIMARY KEY,
    query_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    revision_number INT NOT NULL,
    relevance_grade INT NOT NULL CHECK (relevance_grade BETWEEN 0 AND 3),
    rationale TEXT NOT NULL,
    adjudicated_by BINARY(16) NOT NULL,
    supersedes_qrel_id BINARY(16),
    adjudicated_at DATETIME(6) NOT NULL,
    UNIQUE (query_id, study_id, revision_number),
    FOREIGN KEY (query_id) REFERENCES evaluation_queries(id),
    FOREIGN KEY (study_id) REFERENCES studies(id),
    FOREIGN KEY (adjudicated_by) REFERENCES user_accounts(id),
    FOREIGN KEY (supersedes_qrel_id) REFERENCES evaluation_qrels(id)
);

CREATE TABLE evaluation_runs (
    id BINARY(16) PRIMARY KEY,
    dataset_version_id BINARY(16) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    comparability_status VARCHAR(32) NOT NULL,
    primary_k INT NOT NULL,
    cutoffs_json JSON NOT NULL,
    repetitions INT NOT NULL,
    execution_seed BIGINT NOT NULL,
    code_build VARCHAR(160) NOT NULL,
    environment_json JSON NOT NULL,
    environment_sha256 CHAR(64) NOT NULL,
    run_manifest_json JSON NOT NULL,
    run_sha256 CHAR(64) NOT NULL,
    started_by BINARY(16) NOT NULL,
    queued_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    failure_reason VARCHAR(1000),
    FOREIGN KEY (dataset_version_id) REFERENCES evaluation_dataset_versions(id),
    FOREIGN KEY (started_by) REFERENCES user_accounts(id)
);

CREATE TABLE evaluation_algorithm_runs (
    id BINARY(16) PRIMARY KEY,
    evaluation_run_id BINARY(16) NOT NULL,
    algorithm_code VARCHAR(48) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    attempt_number INT NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    configuration_json JSON NOT NULL,
    configuration_sha256 CHAR(64) NOT NULL,
    unavailable_reason VARCHAR(1000),
    index_build_millis BIGINT,
    latency_p50_millis DECIMAL(14,4),
    latency_p95_millis DECIMAL(14,4),
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    UNIQUE (evaluation_run_id, algorithm_code, attempt_number),
    FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_runs(id)
);

CREATE TABLE evaluation_ranked_hits (
    algorithm_run_id BINARY(16) NOT NULL,
    query_id BINARY(16) NOT NULL,
    study_id BINARY(16) NOT NULL,
    candidate_rank INT NOT NULL,
    retrieval_score DECIMAL(16,8) NOT NULL,
    PRIMARY KEY (algorithm_run_id, query_id, candidate_rank),
    UNIQUE (algorithm_run_id, query_id, study_id),
    FOREIGN KEY (algorithm_run_id) REFERENCES evaluation_algorithm_runs(id),
    FOREIGN KEY (query_id) REFERENCES evaluation_queries(id),
    FOREIGN KEY (study_id) REFERENCES studies(id)
);

CREATE TABLE evaluation_query_metrics (
    algorithm_run_id BINARY(16) NOT NULL,
    query_id BINARY(16) NOT NULL,
    cutoff_k INT NOT NULL,
    metric_status VARCHAR(32) NOT NULL,
    precision_value DECIMAL(14,10),
    recall_value DECIMAL(14,10),
    f1_value DECIMAL(14,10),
    mrr_value DECIMAL(14,10),
    ndcg_value DECIMAL(14,10),
    relevant_count INT NOT NULL,
    judged_count INT NOT NULL,
    PRIMARY KEY (algorithm_run_id, query_id, cutoff_k),
    FOREIGN KEY (algorithm_run_id) REFERENCES evaluation_algorithm_runs(id),
    FOREIGN KEY (query_id) REFERENCES evaluation_queries(id)
);

CREATE TABLE evaluation_aggregate_metrics (
    algorithm_run_id BINARY(16) NOT NULL,
    cutoff_k INT NOT NULL,
    metric_status VARCHAR(32) NOT NULL,
    precision_value DECIMAL(14,10),
    recall_value DECIMAL(14,10),
    f1_value DECIMAL(14,10),
    mrr_value DECIMAL(14,10),
    ndcg_value DECIMAL(14,10),
    eligible_query_count INT NOT NULL,
    excluded_query_count INT NOT NULL,
    PRIMARY KEY (algorithm_run_id, cutoff_k),
    FOREIGN KEY (algorithm_run_id) REFERENCES evaluation_algorithm_runs(id)
);

CREATE TABLE evaluation_resource_snapshots (
    id BINARY(16) PRIMARY KEY,
    algorithm_run_id BINARY(16) NOT NULL,
    sample_phase VARCHAR(32) NOT NULL,
    sample_order INT NOT NULL,
    wall_millis DECIMAL(14,4),
    process_cpu_nanos BIGINT,
    heap_used_bytes BIGINT,
    heap_committed_bytes BIGINT,
    captured_at DATETIME(6) NOT NULL,
    UNIQUE (algorithm_run_id, sample_phase, sample_order),
    FOREIGN KEY (algorithm_run_id) REFERENCES evaluation_algorithm_runs(id)
);

CREATE INDEX idx_evaluation_versions_status ON evaluation_dataset_versions(dataset_status, created_at);
CREATE INDEX idx_evaluation_queries_version ON evaluation_queries(dataset_version_id, query_split);
CREATE INDEX idx_evaluation_judgments_pair ON evaluation_judgments(query_id, study_id, reviewer_id, revision_number);
CREATE INDEX idx_evaluation_qrels_query ON evaluation_qrels(query_id, study_id, revision_number);
CREATE INDEX idx_evaluation_runs_dataset ON evaluation_runs(dataset_version_id, queued_at);
CREATE INDEX idx_evaluation_algorithm_status ON evaluation_algorithm_runs(evaluation_run_id, run_status);

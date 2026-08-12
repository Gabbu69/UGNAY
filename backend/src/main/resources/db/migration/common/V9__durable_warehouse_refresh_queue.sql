-- Transactional outbox for catalogue/completion-triggered warehouse refreshes.
-- Source mutations and their refresh request commit together; a bounded local
-- worker can recover QUEUED/RUNNING work after restart.

CREATE TABLE warehouse_refresh_requests (
    id BINARY(16) PRIMARY KEY,
    requested_by BINARY(16) NOT NULL,
    trigger_code VARCHAR(48) NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    warehouse_load_id BINARY(16),
    requested_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    failure_reason VARCHAR(1000),
    FOREIGN KEY (requested_by) REFERENCES user_accounts(id),
    FOREIGN KEY (warehouse_load_id) REFERENCES warehouse_loads(id)
);

CREATE INDEX idx_warehouse_refresh_queue
    ON warehouse_refresh_requests(request_status, requested_at);

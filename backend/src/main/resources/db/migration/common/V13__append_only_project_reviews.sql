-- Project-scoped research review conversations. Queue records remain durable;
-- every revision request/response is append-only and actor-attributed.

CREATE TABLE research_review_events (
    id BINARY(16) PRIMARY KEY,
    review_id BINARY(16) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    message_text VARCHAR(2000) NOT NULL,
    evidence_location VARCHAR(1000),
    actor_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (review_id) REFERENCES review_queue_items(id),
    FOREIGN KEY (actor_id) REFERENCES user_accounts(id)
);

CREATE INDEX idx_research_review_event_history ON research_review_events(review_id, created_at);

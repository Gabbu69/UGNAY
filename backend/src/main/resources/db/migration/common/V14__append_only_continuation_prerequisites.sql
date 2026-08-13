-- Continuation prerequisites are authoritative research evidence.  Preserve every
-- submitted revision and its exact objective-to-open-work mappings instead of
-- replacing the prior state in the V5 compatibility tables.

CREATE TABLE proposal_continuation_revisions (
    id BINARY(16) PRIMARY KEY,
    proposal_id BINARY(16) NOT NULL,
    predecessor_study_id BINARY(16) NOT NULL,
    revision_number BIGINT NOT NULL,
    code_access_confirmed BOOLEAN NOT NULL,
    data_access_confirmed BOOLEAN NOT NULL,
    access_notes TEXT NOT NULL,
    recorded_by BINARY(16) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE (proposal_id, revision_number),
    FOREIGN KEY (proposal_id) REFERENCES proposals(id),
    FOREIGN KEY (predecessor_study_id) REFERENCES studies(id),
    FOREIGN KEY (recorded_by) REFERENCES user_accounts(id)
);

CREATE INDEX idx_continuation_revision_latest
    ON proposal_continuation_revisions(proposal_id, predecessor_study_id, revision_number);

CREATE TABLE proposal_continuation_revision_links (
    revision_id BINARY(16) NOT NULL,
    proposal_objective_id BINARY(16) NOT NULL,
    continuation_item_id BINARY(16) NOT NULL,
    rationale TEXT NOT NULL,
    PRIMARY KEY (revision_id, proposal_objective_id, continuation_item_id),
    FOREIGN KEY (revision_id) REFERENCES proposal_continuation_revisions(id),
    FOREIGN KEY (proposal_objective_id) REFERENCES proposal_objectives(id),
    FOREIGN KEY (continuation_item_id) REFERENCES continuation_items(id)
);

CREATE INDEX idx_continuation_revision_link_objective
    ON proposal_continuation_revision_links(revision_id, proposal_objective_id);

-- Retain any evidence authored before V14 as immutable revision one.  Reusing
-- the proposal UUID as the revision UUID is safe because the tables have
-- independent key spaces and makes the compatibility backfill deterministic.
INSERT INTO proposal_continuation_revisions(
    id, proposal_id, predecessor_study_id, revision_number,
    code_access_confirmed, data_access_confirmed, access_notes,
    recorded_by, recorded_at
)
SELECT proposal_id, proposal_id, predecessor_study_id, 1,
       code_access_confirmed, data_access_confirmed, access_notes,
       recorded_by, recorded_at
FROM proposal_continuation_evidence;

INSERT INTO proposal_continuation_revision_links(
    revision_id, proposal_objective_id, continuation_item_id, rationale
)
SELECT proposal_id, proposal_objective_id, continuation_item_id, rationale
FROM proposal_objective_continuation_links links
WHERE EXISTS (
    SELECT 1 FROM proposal_continuation_evidence evidence
    WHERE evidence.proposal_id = links.proposal_id
);

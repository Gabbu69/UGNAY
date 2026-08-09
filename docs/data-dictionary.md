# Data Dictionary

This is the v1 logical dictionary and integrity contract. Flyway migrations under `backend/src/main/resources/db/migration` are the executable schema authority. Names below use `snake_case`; API representations use `camelCase` and expose UUIDs as strings.

The runtime identity provider persists the bootstrap administrator, active credentials and roles, hashed invitation tokens, and project memberships. The browser account panel implements session handling plus curator invitation and selected-project membership controls; raw token acceptance remains API-only.

## Shared conventions

| Convention | Definition |
|---|---|
| Primary key | `id BINARY(16)` UUID unless a composite key is listed |
| Time | UTC `DATETIME(6)`; mutable records have `created_at`, `updated_at` |
| Concurrency | Aggregate roots have numeric `row_version` for optimistic locking/ETags |
| Lifecycle | Scholarly/history records use status plus archive/void metadata; no hard delete after publication or approval |
| Text | `utf8mb4`, normally `utf8mb4_0900_ai_ci`; normalized identifiers get explicit unique indexes |
| Money/files | No file BLOBs in MySQL; Lite uses private filesystem objects and Compose uses MinIO |
| Frozen evidence | Approved decisions, baselines, algorithm configurations, and audit events are insert-only |

## Identity and governance

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `departments` | `code`, `name`, `active` | Unique institutional department code |
| `user_accounts` | `department_id`, `email`, `display_name`, `status`, `last_login_at`, `row_version` | Unique normalized email; invite-only identity |
| `password_credentials` | `user_id`, `password_hash`, `changed_at`, `failed_attempts`, `locked_until` | One active Argon2id credential per local account |
| `roles` | `code`, `name` | Seeded role codes: student, adviser, coordinator, curator/admin |
| `user_roles` | `user_id`, `role_id`, `granted_by`, `granted_at`, `revoked_at` | Unique active user/role grant; changes audited |
| `invitations` | `email`, `token_hash`, `intended_role`, `expires_at`, `accepted_at`, `invited_by` | 72-hour single-use token; raw value appears only in the creation response and is never stored |
| `project_memberships` | `project_id`, `user_id`, `membership_role`, `active_from`, `active_until` | Project-level authorization separate from global roles |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | Spring-managed session identifier, principal, expiry, and serialized attributes | JDBC session persistence created by Flyway; not an account store |

## Catalogue and documents

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `studies` | `department_id`, `institutional_code`, `title`, `abstract_text`, `status`, `completion_year`, `methodology`, `system_type`, `intended_users`, `site_context`, `source_project_id`, `visibility`, `row_version` | Unique institutional code; at most one study per completed source project |
| `authors` | `display_name`, `normalized_name`, `orcid` | Reusable author identity without requiring a platform account |
| `study_authors` | `study_id`, `author_id`, `author_order`, `author_role` | Composite primary key; unique order within a study |
| `study_objectives` | `study_id`, `sequence_no`, `objective_text`, `normalized_text` | Ordered, independently comparable objectives |
| `taxonomy_terms` | `term_type`, `preferred_label`, `normalized_label`, `language`, `active` | Curated domain, concept, stakeholder, and technology vocabulary |
| `study_terms` | `study_id`, `term_id`, `source`, `confidence` | Unique study/term pair; manual terms distinguishable from extraction |
| `continuation_items` | `study_id`, `item_type`, `statement`, `status`, `evidence_document_version_id` | Structured limitation, recommendation, unfinished work, or known issue |
| `study_relationships` | `source_study_id`, `target_study_id`, `relationship_type`, `rationale`, `created_by` | Directed explicit lineage/reference; self-links rejected |
| `documents` | `owner_type`, `owner_id`, `document_purpose`, `created_at` | Logical file attached only to a meaningful domain record; an import job owns its restricted study-source document |
| `document_versions` | `document_id`, `version_number`, `object_key`, `original_filename`, `mime_type`, `byte_size`, `sha256`, `uploaded_by`, `visibility`, `scan_status`, `extraction_status`, `storage_etag` | Unique randomized object key and document/version; immutable after private storage confirmation |
| `import_batches` | `imported_by`, `source_name`, `import_status`, `total_rows`, `accepted_rows`, `rejected_rows`, `created_at`, `completed_at` | Schema reserved for future CSV batches; the current curator endpoint accepts one reviewed JSON study record per request |
| `extraction_runs` | `document_version_id`, `extractor_version`, `run_status`, `queued_at`, `started_at`, `completed_at`, `progress_percent`, `page_count`, `extracted_character_count`, `max_character_count`, `timeout_seconds`, `attempt_count`, `manual_review_required`, `publication_eligible`, `failure_reason` | Durable PDF job and extraction attempt; indexed by status/queue time and recoverable after restart |
| `document_segments` | `extraction_run_id`, `segment_order`, `page_number`, `section_label`, `extracted_text` | Bounded extracted-text chunks tied to the exact run; not published merely because extraction succeeded |

## Search and algorithm evidence

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `study_search_profiles` | `study_id`, normalized title/problem/objectives/features/methodology/context fields, `combined_text`, `profile_hash`, `status` | One active profile per published study; full-text indexes cover normalized text |
| `search_term_statistics` | `algorithm_configuration_id`, `term`, `document_frequency`, `inverse_document_frequency` | Reproducible TF-IDF corpus snapshot |
| `model_versions` | `name`, `revision`, `sha256`, `dimension`, `status`, `loaded_at` | Records the locally verified model asset; never contains model bytes |
| `study_embeddings` | `study_id`, `model_version_id`, `profile_hash`, `dimension`, `vector_bytes` | Rebuildable float vector; unique by study/model/profile |
| `segment_embeddings` | `segment_id`, `model_version_id`, `text_hash`, `dimension`, `vector_bytes` | Optional passage-level derived data |
| `algorithm_configurations` | `version_name`, `model_version_id`, `weights_json`, `thresholds_json`, `concept_map_hash`, `stopword_hash`, `active_from` | Immutable, checksummed scoring contract |

## Intake, discovery, and academic decisions

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `problem_cases` | `department_id`, `submitted_by`, `title`, `problem_statement`, `stakeholder_name`, `affected_users`, `site_context`, `desired_outcome`, `constraints_text`, `privacy_classification`, `status`, `row_version` | Structured real-world problem, not a project task |
| `problem_evidence` | `problem_case_id`, `evidence_type`, `statement`, `document_id`, `source_date` | A problem needs reviewable evidence before routing |
| `proposals` | `problem_case_id`, `working_title`, `solution_summary`, `features_text`, `methodology`, `data_sources`, `system_type`, `intended_users`, `status`, `row_version` | One or more revisions may address a problem case |
| `proposal_objectives` | `proposal_id`, `sequence_no`, `objective_text`, `novelty_claim`, `measurement_baseline`, `measurement_target`, `evaluation_method` | Ordered inputs for one-to-one objective comparison |
| `discovery_runs` | `proposal_id`, `input_hash`, `algorithm_configuration_id`, `model_hash`, `assessment_status`, `recommendation`, `confidence`, `started_at`, `completed_at` | Frozen run; `PARTIAL` is retained rather than overwritten |
| `discovery_candidates` | `discovery_run_id`, `study_id`, `rank_no`, `problem_score`, `solution_score`, `objective_score`, `confidence`, `similarity_band`, `duplicate_flag`, `exact_match_type` | Unique study per run and rank; top explained candidates |
| `candidate_evidence` | `candidate_id`, `field_name`, `component_type`, `component_score`, `proposal_excerpt`, `study_segment_id`, `redaction_status` | Score decomposition and safe matched passage |
| `proposal_decisions` | `proposal_id`, `discovery_run_id`, `decision_type`, `disposition`, `rationale`, `decided_by`, `decided_at`, `supersedes_id` | Adviser recommendation and coordinator disposition are separate immutable rows |
| `decision_target_studies` | `decision_id`, `study_id`, `target_role` | Exactly one primary predecessor for approved improve/continue; references may repeat |

## Project and traceability

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `projects` | `proposal_id`, `department_id`, `code`, `title`, `route`, `stage`, `current_baseline_id`, `row_version` | Created only by an approval disposition |
| `project_predecessors` | `project_id`, `study_id`, `lineage_type`, `primary_predecessor`, `rationale` | One primary predecessor for improve/continue; cycle checked at service layer |
| `trace_items` | `project_id`, `item_key`, `item_type`, `status`, `current_revision_id`, `row_version` | Stable identity for problem, objective, requirement, feature, test, or output |
| `trace_item_revisions` | `trace_item_id`, `revision_no`, `title`, `statement`, `rationale`, `priority`, `created_by`, `created_at`, `supersedes_id` | Immutable revision history |
| `requirement_details` | `revision_id`, `requirement_type`, `actor`, `behavior`, `outcome`, `acceptance_criteria`, `verification_method`, `data_and_error_details` | Structured readiness inputs; revision type must be requirement |
| `test_case_details` | `revision_id`, `mandatory`, `procedure_text`, `expected_result` | Test definition separate from executions |
| `output_details` | `revision_id`, `output_type`, `evidence_document_id`, `repository_reference_id` | Final deliverable evidence |
| `trace_links` | `project_id`, `source_item_id`, `target_item_id`, `link_type`, `status`, `created_by`, `row_version` | Typed active working link; composite keys prohibit cross-project edges |
| `project_baselines` | `project_id`, `version_no`, `status`, `approved_by`, `approved_at`, `source_change_request_id`, `baseline_hash` | Approved baseline is immutable and uniquely numbered |
| `baseline_items` | `baseline_id`, `trace_item_id`, `revision_id` | Exact revision frozen in a baseline |
| `baseline_links` | `baseline_id`, `trace_link_id`, `source_revision_id`, `target_revision_id` | Exact edge and endpoints frozen in a baseline |

## Change, testing, and continuity

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `change_requests` | `project_id`, `base_baseline_id`, `title`, `rationale`, `boundary_changes`, `status`, `impact_calculated_at`, `row_version` | Approval rejected when the base baseline is stale |
| `change_request_items` | `change_request_id`, `trace_item_id`, `operation`, `proposed_revision_id` | Adds, modifies, retires, or relinks project evidence |
| `impact_paths` | `change_request_id`, `source_item_id`, `impacted_item_id`, `path_text`, `depth`, `severity`, `stales_evidence` | One impacted artifact per request, retaining the shortest/explaining path |
| `change_decisions` | `change_request_id`, `disposition`, `rationale`, `decided_by`, `decided_at` | Immutable coordinator action |
| `test_executions` | `test_case_id`, `baseline_id`, `build_reference`, `status`, `executed_by`, `executed_at`, `evidence_document_version_id`, `current` | Current only when test, target requirements, baseline, and build still match |
| `repository_references` | `project_id`, `provider`, `repository_url`, `commit_hash`, `release_tag`, `access_status`, `license_name` | Reference only; no credentials or Git hosting |
| `release_snapshots` | `project_id`, `baseline_id`, `repository_reference_id`, `released_at`, `setup_document_id` | Reproducible output anchor |
| `completion_packages` | `project_id`, `status`, readiness dimensions, `rights_confirmed`, `approved_by`, `completed_at`, `row_version` | One approved completion package per project |
| `completion_package_items` | `completion_package_id`, `item_type`, `document_id`, `repository_reference_id`, `statement`, `complete` | Required handoff components |
| `continuation_item_claims` | `successor_project_id`, `continuation_item_id`, `objective_item_id`, `claimed_by`, `claimed_at`, `status` | Successor claims predecessor work without editing it |
| `continuation_claim_outcomes` | `claim_id`, `outcome_type`, `statement`, `evidence_item_id`, `recorded_at` | Completed, partial, deferred, or invalidated result |

## Analysis, health, and audit

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `analysis_runs` | `project_id`, `baseline_id`, `analysis_type`, `rule_set_version`, `assessment_status`, `started_at`, `completed_at` | Frozen alignment, scope, verification, health, or continuity analysis |
| `findings` | `analysis_run_id`, `rule_code`, `severity`, `state`, `title`, `explanation`, `accepted_by`, `acceptance_rationale`, `acceptance_expires_at` | Accepted findings require coordinator, rationale, and expiry |
| `finding_evidence` | `finding_id`, `trace_item_id`, `trace_link_id`, `document_version_id`, `evidence_text`, `next_action` | Concrete implicated record and remediation path |
| `health_snapshots` | `project_id`, `baseline_id`, `alignment_score`, `readiness_score`, `verification_score`, `scope_score`, `continuity_score`, `overall_band`, `calculated_at` | Independent dimensions; overall equals worst applicable band |
| `analysis_jobs` | `job_type`, `subject_type`, `subject_id`, `job_status`, `progress_percent`, `failure_reason`, `created_at`, `started_at`, `completed_at` | Schema-reserved record for later asynchronous analyses; current PDF ingestion uses durable `extraction_runs`, not this table |
| `audit_events` | `occurred_at`, `actor_id`, `action`, `subject_type`, `subject_id`, `correlation_id`, `ip_hash`, `before_json`, `after_json` | Append-only security and academic accountability record |

## Critical constraints and indexes

- Unique: normalized user email, department code, project code, institutional study code, study `source_project_id`, `(project_id, item_key)`, `(trace_item_id, revision_no)`, `(project_id, version_no)`, and active study/model/profile embedding.
- Full-text: the normalized fields in `study_search_profiles`; full-text output supplies candidates but never the final explained score alone.
- Traversal: indexes on both `(project_id, source_item_id)` and `(project_id, target_item_id)` for trace links.
- Review queues: indexes on status plus department/project and creation time.
- Audit: index on `(subject_type, subject_id, occurred_at)` and `(actor_id, occurred_at)`; no update/delete application repository.
- Lineage self-links are rejected by a check constraint. Ancestor cycles and the single-primary-predecessor rule are validated transactionally before insert.
- Completing a project and creating its catalogue study occur in one transaction; a uniqueness constraint makes retries idempotent.

## Runtime projection support (Flyway V3)

The executable V3 migration adds relational child tables needed to reconstruct the complete browser workspace without serializing its evidence chain. These include `discovery_revision_checklist`, `candidate_evidence_components`, `candidate_component_terms`, `project_team_members`, `trace_coverage_snapshots`, `scope_risk_snapshots`, `scope_risk_explanations`, `change_request_boundary_flags`, `impact_previews`, `impact_path_nodes`, `impact_documents_to_revise`, `completion_criteria`, `lineage_nodes`, `lineage_edges`, `health_dimensions`, and `review_queue_items`. It also adds the project/current-baseline composite constraint so a project cannot point at another project's baseline. Core relationships remain queryable rows; inherited JSON columns are used only for frozen configuration placeholders.

## Durable extraction support (Flyway V4)

V4 adds `document_versions.storage_etag` and the queue, progress, limits, retry, manual-review, and publication-eligibility columns on `extraction_runs`, plus `(run_status, queued_at)` for bounded backlog pickup. The extraction row—not an in-memory task or SSE connection—is the recovery authority. A `RUNNING` job is returned to `QUEUED` after application restart; a `VALIDATING` job whose storage confirmation is uncertain becomes `ORPHAN_REVIEW` and cannot be published automatically.

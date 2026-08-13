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
| `studies` | `department_id`, `institutional_code`, `title`, `abstract_text`, `academic_year`, validated `completion_year`, `results_text`, `methodology`, `system_type`, `intended_users`, `site_context`, `source_project_id`, `visibility`, `row_version` | Unique institutional code; at most one study per completed source project; raw academic year is retained when numeric year is unavailable |
| `study_metadata_versions` | `study_id`, `version_number`, `provenance_type`, `source_sha256`, `metadata_json`, `recorded_by`, `recorded_at` | Immutable exact metadata history; legacy rows are snapshotted without inventing absent fields |
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
| `study_search_profiles` | `study_id`, `metadata_version_id`, `normalizer_version`, title/problem/objectives/methodology/keyword text, `combined_text`, `profile_sha256`, `profile_status` | Versioned, rebuildable search profile tied to exact metadata when available |
| `retrieval_corpus_snapshots` | `snapshot_name`, `source_cutoff`, `corpus_sha256`, `study_count`, `snapshot_status`, `created_by`, `created_at` | Named immutable retrieval-corpus identity |
| `retrieval_corpus_items` | `corpus_snapshot_id`, `study_id`, `profile_id`, `item_order` | Exact ordered study/profile membership of one corpus snapshot |
| `search_term_statistics` | `corpus_snapshot_id`, `term_text`, `document_frequency`, `inverse_document_frequency` | Reproducible TF-IDF statistics for one corpus snapshot |
| `model_versions` | `name`, `revision`, `sha256`, `dimension`, `status`, `loaded_at` | Records the locally verified model asset; never contains model bytes |
| `study_embeddings` | `study_id`, `model_version_id`, `profile_hash`, `dimension`, `vector_bytes` | Rebuildable float vector; unique by study/model/profile |
| `segment_embeddings` | `segment_id`, `model_version_id`, `text_hash`, `dimension`, `vector_bytes` | Optional passage-level derived data |
| `algorithm_configurations` | `version_name`, `algorithm_code`, `model_version_id`, `weights_json`, `thresholds_json`, `concept_map_hash`, `stopword_hash`, `reproducibility_status`, `active_from` | Immutable scoring contract; inherited incomplete records remain explicitly `LEGACY_PARTIAL` |

## Intake, discovery, and academic decisions

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `problem_cases` | `department_id`, `submitted_by`, `title`, `problem_statement`, `stakeholder_name`, `affected_users`, `site_context`, `desired_outcome`, `constraints_text`, `privacy_classification`, `status`, `row_version` | Structured real-world problem, not a project task |
| `problem_evidence` | `problem_case_id`, `evidence_type`, `statement`, `document_id`, `source_date` | A problem needs reviewable evidence before routing |
| `proposals` | `problem_case_id`, `working_title`, `solution_summary`, `features_text`, `methodology`, `data_sources`, `system_type`, `intended_users`, `status`, `row_version` | One or more revisions may address a problem case |
| `proposal_objectives` | `proposal_id`, `sequence_no`, `objective_text`, `novelty_claim`, `measurement_baseline`, `measurement_target`, `evaluation_method` | Ordered inputs for one-to-one objective comparison |
| `discovery_runs` | `proposal_id`, `input_hash`, `algorithm_configuration_id`, `model_hash`, `corpus_snapshot_id`, `environment_snapshot_json`, `code_version`, `assessment_status`, `recommendation`, `confidence`, `started_at`, `completed_at` | Frozen run; `PARTIAL` and legacy partial-reproducibility evidence are retained rather than overwritten |
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
| `completion_packages` | `project_id`, `package_status`, `readiness_state`, nullable projected `readiness_score`, derived `code_data_rights_confirmed`, `completed_by`, `completed_at`, `row_version` | One completion package per project; the score is serialized only when every criterion has a numeric assessed value |
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

V11 persists atomic intake retry keys and generic evidence references, binds impact previews to operation-set versions and digests, and project-scopes review items. V12 adds assessed state, source, and time to completion criteria plus append-only independent reference verifications. V13 adds actor-attributed, append-only research review events for revision requests and responses; queue items are no longer deleted and regenerated during startup. V14 adds append-only `proposal_continuation_evidence_revisions` plus `proposal_continuation_revision_objectives`; each save creates a new revision and objective-link set, while the latest revision drives the assessed route state without rewriting V5 history. V15 makes `discovery_runs.confidence_score` nullable so a run with no eligible candidate records `UNASSESSED` plus `NULL`, never a fabricated zero.

## Durable extraction support (Flyway V4)

V4 adds `document_versions.storage_etag` and the queue, progress, limits, retry, manual-review, and publication-eligibility columns on `extraction_runs`, plus `(run_status, queued_at)` for bounded backlog pickup. The extraction row—not an in-memory task or SSE connection—is the recovery authority. A `RUNNING` job is returned to `QUEUED` after application restart; a `VALIDATING` job whose storage confirmation is uncertain becomes `ORPHAN_REVIEW` and cannot be published automatically.

## Catalogue and retrieval foundation (Flyway V6)

V6 appends `studies.results_text` and validated numeric `studies.completion_year`; neither column is populated with guessed data. It adds immutable `study_metadata_versions`, derived `study_search_profiles`, `retrieval_corpus_snapshots`, ordered `retrieval_corpus_items`, and `search_term_statistics`. Algorithm configurations gain an explicit algorithm code and reproducibility status; discovery runs can reference a corpus snapshot, environment snapshot, and code version. Existing evidence is preserved and incomplete inherited configuration remains `LEGACY_PARTIAL` rather than being rewritten as reproducible.

## Retrieval evaluation framework (Flyway V7)

| Table | Important columns | Integrity and purpose |
|---|---|---|
| `evaluation_datasets` | `name`, `description`, `created_by`, `created_at` | Logical curator-authored experiment dataset |
| `evaluation_dataset_versions` | `dataset_id`, `version_number`, `dataset_status`, corpus/dataset SHA-256, `manifest_json`, creator/freezer and times | `DRAFT` authoring boundary and immutable `FROZEN` manifest |
| `evaluation_corpus_items` | `dataset_version_id`, `study_id`, `item_order`, `study_profile_sha256`, `profile_text`, `study_snapshot_json` | Exact copied corpus used identically by every arm |
| `evaluation_queries` | `dataset_version_id`, `external_key`, `query_split`, title/text/snapshot, `query_sha256`, creator/time | Structured `DEV`/`TEST` input, not an ad-hoc live query |
| `evaluation_judgments` | query/study/reviewer, `revision_number`, `relevance_grade`, `rationale`, `supersedes_judgment_id`, `judged_at` | Append-only 0-3 independent reviewer evidence |
| `evaluation_qrels` | query/study, revision, grade/rationale, adjudicator, superseded qrel, time | Append-only coordinator-adjudicated ground truth; never algorithm output |
| `evaluation_runs` | dataset version, run/comparability status, primary K/cutoffs, repetitions/seed, build, environment/run manifests and hashes, lifecycle times | Durable asynchronous four-arm comparison authority |
| `evaluation_algorithm_runs` | run, algorithm/version/attempt/status, configuration JSON/SHA-256, availability reason, index time, p50/p95 | One versioned strategy attempt and its completeness/performance state |
| `evaluation_ranked_hits` | algorithm run, query, study, rank, score | Deterministic top-ten experimental ranking |
| `evaluation_query_metrics` | algorithm run, query, K, metric status, P/R/F1/MRR/NDCG, relevant/judged counts | Exact per-query IR evidence; nullable values represent unavailable measurement |
| `evaluation_aggregate_metrics` | algorithm run, K, metric status, P/R/F1/MRR/NDCG, eligible/excluded counts | Macro average over eligible queries with explicit exclusions |
| `evaluation_resource_snapshots` | algorithm run, phase/order, wall time, process CPU, heap used/committed, capture time | Before/index/query/after resource samples; unsupported measurements remain null |

Dataset freeze is rejected until each query has a positive adjudicated qrel and every adjudicated pair retains two distinct current reviewers. The database preserves revisions and job evidence across restart. Evaluation tables do not reference or update proposal decisions.

## Historical research warehouse (Flyway V8)

The warehouse uses the same MySQL instance. Operational tables remain authoritative; `warehouse_*` rows record load work and `dw_*` rows are immutable analytical copies.

| Group | Tables | Purpose |
|---|---|---|
| Load ledger | `warehouse_loads`, `warehouse_load_stages`, `warehouse_quality_issues` | Durable request, six ordered stage states, counts, source hash/cutoff, failure, publication link, and field-level quality evidence |
| Staging | `warehouse_staged_studies`, `warehouse_staged_objectives`, `warehouse_staged_metadata_versions`, `warehouse_staged_topics`, `warehouse_staged_retrievals`, `warehouse_staged_continuity` | Load-scoped exact source copies and derived normalized/validated values; never an application source of truth |
| Snapshot | `warehouse_snapshots` | Immutable version, source SHA-256/cutoff/counts, and `BUILDING`/`PUBLISHED` boundary; only complete loads publish |
| Dimensions | `dw_department_dimensions`, `dw_year_dimensions`, `dw_topic_dimensions`, `dw_study_dimensions`, `dw_study_version_dimensions` | Snapshot-scoped department, validated year, explicit taxonomy, complete study, and metadata-history attributes |
| Bridge/facts | `dw_study_topic_bridge`, `dw_study_objective_facts`, `dw_study_facts`, `dw_retrieval_facts`, `dw_continuation_facts` | Many-to-many topics plus objective, study-count, historical retrieval, and continuation evidence |

The ordered stage codes are `COLLECT`, `VALIDATE`, `CLEAN`, `TRANSFORM`, `STORE`, and `ANALYZE`. Strict year derivation accepts a four-digit year or consecutive `YYYY-YYYY` within 1900-2200; invalid or absent values remain null and create a quality issue. Source text is not replaced by cleaned text. The same source SHA-256 produces an `UNCHANGED` load, and a failed load retains the previous published snapshot.

Warehouse analytics count distinct authorized studies. Repeated topics require an active topic/keyword on at least two studies; common research areas require an explicit active `RESEARCH_AREA`; trends are observed topic-by-year counts with no forecasting. Continuation facts retain their source kind, predecessor/successor/claim references, status, rationale, and evidence time without manufacturing a relationship when evidence is absent.

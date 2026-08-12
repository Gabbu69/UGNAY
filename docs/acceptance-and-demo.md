# Acceptance and Demo Guide

## Demo story

Use one coherent campus problem instead of unrelated CRUD records. The recommended fixture is a flood-readiness reporting project with:

- A completed predecessor with a documented notification limitation.
- An unfinished predecessor with a reusable repository and one open continuation item.
- A superficially similar study serving a different stakeholder and site.
- English/Filipino concepts such as `baha` and `flood`.
- One intentionally unjustified feature and one Must requirement whose test later becomes stale.

This fixture is intended to exercise ranking explanations, human routing, trace findings, approved growth, change impact, and continuity in a single narrative. It is a demonstration plan, not evidence that every release gate has passed.

Synthetic fixtures may demonstrate controls and UI states, but must be visibly labelled and must never be presented as thesis evaluation evidence. Real algorithm metrics remain `UNASSESSED` until an institutional corpus, query set, and qrels are independently reviewed, adjudicated, frozen, and run.

## Integrated defense journey

This is the shortest coherent demonstration of the three subject areas and the research framework:

1. Sign in as an authorized user and open `/research-lab/query`.
2. Execute `FIND THESIS WHERE TOPIC = "flood" AND YEAR >= 2022 USING TFIDF ORDER BY RELEVANCE LIMIT 5` with processing trace enabled.
3. Show the real tokens and source spans, AST, completed semantic validation, typed allow-listed action, algorithm version, warehouse snapshot/as-of reference, authorized results, and readable explanations. Enter a semicolon/SQL keyword and show the stage-specific safe diagnostic rather than a database error.
4. Open `/research-lab/warehouse`. Show the latest successful `Collect -> Validate -> Clean -> Transform -> Store -> Analyze` stages, source hash/count, strict-year quality issues, distinct-study tables, repeated topics, explicit research areas, observed trends, and continuation history. If no snapshot exists, demonstrate the honest `UNASSESSED` state; a curator may then refresh actual catalogue evidence.
5. Open `/research-lab/evaluation`. Select a genuinely frozen dataset and show the identical corpus SHA-256, structured query hashes/splits, two-reviewer/adjudicated qrel completeness, algorithm configurations, environment hash, and durable run status.
6. Show the lexical, TF-IDF, semantic, and hybrid rows at primary K=5, then inspect all K values, p50/p95 latency, resource evidence, and export. If semantic inference is unavailable, show `UNAVAILABLE`/`PARTIAL` rather than hiding or replacing it.
7. Return to Decision Room and state the boundary: the experiment compares retrieval evidence only; an adviser recommends and a coordinator decides.

## Role journey

1. **Curator:** open **Ingest research** in the Research Atlas, register the three prior studies individually as reviewed metadata, upload their PDFs separately, and review durable extraction state and restricted visibility. CSV batch ingestion is not part of this pilot journey.
2. **Student:** create the problem case, evidence, objectives, and solution proposal; run discovery.
3. **Adviser:** inspect field bars, one-to-one objective matches, and safe excerpts; record a recommendation.
4. **Coordinator:** approve `CONTINUE` against the unfinished primary predecessor and record the rationale.
5. **Student:** create requirements, features, tests, outputs, and links; view graph and matrix alternatives.
6. **Adviser:** resolve incomplete-requirement findings and expose the unjustified feature.
7. **Coordinator:** approve baseline 1.
8. **Student:** propose a boundary-changing feature; preview the blast radius and stale tests in Change Lab.
9. **Coordinator:** approve the change and baseline 2; verify old test evidence is now stale.
10. **Student/Coordinator:** complete the handoff package, publish the result as a study, and view predecessor/successor lineage.

The persistent bootstrap administrator may perform every role in a local demo. Open the top-bar account control, sign in, confirm every live role badge, and use the curator access desk to issue separate role invitations and grant selected-project memberships. The one-time token must be copied when created and accepted through the public invitation API before that account signs in. The UI distinguishes live data from unavailable data and never substitutes authoritative-looking demo records.

## Previously verified baseline versus target/pending — 2026-08-09

“Verified here” means directly observed for the pre-framework baseline on that date. It does not promote a target into a completed release gate, and it does not by itself verify the later V6-V8/RQL/evaluation/warehouse upgrade. Final handoff must append fresh full-gate results instead of silently reusing these counts.

| Verified here | Result |
|---|---|
| Frontend checks | ESLint passed; Vitest passed 6 files / 22 tests; Vite production build passed with route-lazy evidence workspaces |
| Backend Maven suite | Maven passed 51 tests across 13 suites with zero failures/errors; the optional external-asset inference test was skipped in the ordinary run |
| INT8 inference | The separately supplied release INT8 model and tokenizer produced a normalized 384-dimensional vector in the focused Java test |
| Static contracts | Every PowerShell script parses; JSON, Compose YAML, Markdown links, and OpenAPI structural lint pass |

| Target or pending | Required evidence still missing |
|---|---|
| Windows Lite physical acceptance | Complete a clean release install, offline restart, Defender scan, backup/restore, failed-update rollback, and memory/timing capture on the actual 4 GB Pentium laptop |
| Container deployment | Start and smoke-test the optional Docker Compose MySQL 8.4, MinIO, ClamAV, application, and Caddy TLS profile |
| Real-service integration | Run the Testcontainers suite against MySQL 8.4 and MinIO |
| Capacity | Measure warm discovery p95 with 10,000 studies and trace analysis with 1,000 nodes |
| Research dataset | Create a real frozen corpus/query set; obtain two independent 0-3 judgments and coordinator qrels; run every arm; report P/R/F1/MRR/NDCG plus availability, latency, resources, hashes, and exclusions |
| Calibration | Use the frozen real dataset to assess Recall@5 and NDCG@5; separately adjudicate route classifications before reporting duplicate false-positive rate |
| Recovery | Complete and document an isolated MySQL plus MinIO backup/restore rehearsal |
| Institutional acceptance | Complete adviser/coordinator UAT, privacy review, and deployment approval |

## Functional acceptance

The following rows are release acceptance conditions. Unless a row is explicitly supported by the dated evidence above, treat it as pending.

| Area | Acceptance condition |
|---|---|
| Identity | Account panel distinguishes anonymous, authenticated, and unavailable session states; login refreshes live workspace data; logout fetches CSRF metadata and invalidates the JDBC session |
| Catalogue | Curator PDF upload returns `202` only after clean scan/private storage/durable queueing; status survives restart, SSE remains optional, and low-text PDFs require manual metadata |
| Catalogue search | Atlas uses paginated server-side search; archived/restricted rows obey server authorization and a row shows similarity only when the current query/proposal actually produced it |
| Discovery | Top candidates show field/component scores, confidence, status, excerpts, model/config version, and redaction |
| Query interpreter | Lexer, parser, AST, semantic validator, typed plan, and executor are the real request path; limits, injection cases, context authorization, diagnostics, trace, warehouse reference, version, redaction, and timeout are tested |
| Evaluation | One frozen hash-addressed corpus/query/qrel manifest feeds all four arms; two-reviewer/adjudication/freeze rules hold; jobs survive restart; metrics and missing resource/model states persist without fabricated zeros |
| Warehouse | Stages execute in order; invalid years remain null with issues; identical source is unchanged; failure does not replace the published snapshot; scoped JSON and CSV contain the same actual aggregates |
| Decision | Engine output cannot change proposal state; adviser and coordinator actions remain distinct and audited |
| Traceability | Evidence Authoring Studio writes ETag-protected items, revisions, links, and executions; graph and matrix render the same filtered chain; cross-project links are rejected |
| Requirements | Missing objective, acceptance criteria, verification method, or measurable NFR threshold blocks baseline readiness |
| Verification | Coverage is requirement-based; duplicate tests do not inflate it; changed targets make evidence stale |
| Scope | No baseline returns `UNASSESSED`; approved expansion is “controlled growth pressure,” not scope creep |
| Change | Preview returns each impacted record once with path and severity; stale-baseline requests require recalculation |
| Completion | Continuity Package Studio records structured handoff evidence; Must verification, weighted coverage, outputs, findings, exceptions, rights, and readiness gate coordinator-only completion |
| Continuity | Completion creates one study; claims do not rewrite predecessors; self/ancestor lineage cycles fail |
| Accessibility | All graph information has a keyboard-operable table/matrix and screen-reader summary |

## Mandatory algorithm scenarios

- Bilingual controlled concepts retrieve a related study and identify why.
- Identical normalized titles with different problem/stakeholder evidence do not become duplicates from title alone.
- Sparse legacy data lowers confidence instead of manufacturing a strong result.
- Maximum-weight objective matching prevents several proposed objectives from reusing one old objective.
- Duplicate classification requires every configured problem, objective, solution, novelty, and confidence threshold.
- Missing ONNX assets create a `PARTIAL` run; lexical weights are not scaled up.
- Restricted content can influence ranking but its excerpt is redacted for an unauthorized viewer.
- A feature without an active approved requirement raises `UNJUSTIFIED_FEATURE`.
- An unapproved Must requirement floors scope risk at High; sensitive/security expansion floors it at Critical.
- A failed or unverified Must requirement during validation makes overall health Critical.
- Expired accepted exceptions block completion.

## Release quality gates (targets)

This section defines the intended acceptance suite. It does not report completed results; the dated ledger above is the evidence authority.

### Automated target suite

- Backend JUnit domain-boundary tests for all threshold edges and deterministic rules.
- Spring Modulith dependency verification.
- Repository/integration tests against MySQL 8.4 and MinIO with Testcontainers.
- Migration tests from an empty database and the last supported release snapshot.
- Authorization tests covering IDOR, department/project membership, state transitions, downloads, and restricted evidence.
- Frontend component tests plus Playwright role journeys.
- Axe, keyboard-only, contrast, reduced-motion, and responsive checks.
- Container build (including backend tests and frontend typecheck/build), Compose configuration, service health, and API/UI same-origin smoke tests.

### Retrieval benchmark target

Use a frozen institutional corpus with approximately 100-200 independently reviewed English/Filipino query-study judgments, enough structured queries to exercise the intended domains, and a held-out `TEST` split. Keep adjudicated labels separate from development and synthetic fixtures. Persist corpus/query/qrel/dataset hashes, algorithm/model/config/build/environment evidence, evaluator completeness, and thresholds. Run lexical, TF-IDF, semantic, and hybrid arms against the identical manifest.

Release targets:

- Recall@5 at least `0.85`.
- NDCG@5 at least `0.75`.
- Possible-duplicate false-positive rate no higher than `10%`.
- Every failed target is reported; do not retune on the held-out evaluation set.

These are targets only. Until the real dataset is frozen and executed, the Research Laboratory must display `UNASSESSED`; a synthetic fixture cannot satisfy them.

### Performance targets

- Warm hybrid discovery under three seconds at p95 for 10,000 studies.
- Trace analysis under two seconds for a 1,000-node project.
- PDF extraction stays asynchronous and never holds the upload request open for parser completion.
- Graph views request a filtered neighborhood, not an entire corpus.

## Release evidence

The pilot is ready to hand over only when the repository includes:

- Exact build/test commands and their dated results.
- OpenAPI output and data dictionary reviewed against the final migration.
- Accessibility and browser workflow report.
- Retrieval calibration dataset description and metric report.
- Authorization/security review and known-risk register.
- Successful isolated backup/restore record.
- Adviser/coordinator UAT sign-off.
- Deployment configuration with secrets removed.
- Explicit list of deferred boundaries and unverified institutional integrations.

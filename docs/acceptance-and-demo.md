# Acceptance and Demo Guide

## Demo story

Use one coherent campus problem instead of unrelated CRUD records. The recommended fixture is a flood-readiness reporting project with:

- A completed predecessor with a documented notification limitation.
- An unfinished predecessor with a reusable repository and one open continuation item.
- A superficially similar study serving a different stakeholder and site.
- English/Filipino concepts such as `baha` and `flood`.
- One intentionally unjustified feature and one Must requirement whose test later becomes stale.

This fixture is intended to exercise ranking explanations, human routing, trace findings, approved growth, change impact, and continuity in a single narrative. It is a demonstration plan, not evidence that every release gate has passed.

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

## Verified here versus target/pending — 2026-08-09

“Verified here” means directly observed in this workspace. It does not promote a target into a completed release gate.

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
| Calibration | Double-review 100–200 English/Filipino pairs and report Recall@5, NDCG@5, and duplicate false-positive rate |
| Recovery | Complete and document an isolated MySQL plus MinIO backup/restore rehearsal |
| Institutional acceptance | Complete adviser/coordinator UAT, privacy review, and deployment approval |

## Functional acceptance

The following rows are release acceptance conditions. Unless a row is explicitly supported by the dated evidence above, treat it as pending.

| Area | Acceptance condition |
|---|---|
| Identity | Account panel distinguishes anonymous, authenticated, and unavailable session states; login refreshes live workspace data; logout fetches CSRF metadata and invalidates the JDBC session |
| Catalogue | Curator PDF upload returns `202` only after clean scan/private storage/durable queueing; status survives restart, SSE remains optional, and low-text PDFs require manual metadata |
| Discovery | Top candidates show field/component scores, confidence, status, excerpts, model/config version, and redaction |
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

Use 100–200 double-reviewed English/Filipino proposal-study pairs. Keep adjudicated labels separate from development fixtures. Record corpus hash, algorithm/model version, evaluator agreement, and thresholds.

Release targets:

- Recall@5 at least `0.85`.
- NDCG@5 at least `0.75`.
- Possible-duplicate false-positive rate no higher than `10%`.
- Every failed target is reported; do not retune on the held-out evaluation set.

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

# Explainable Decision Rules

This document is the human-readable v1 rule contract. Runtime responses must identify the exact algorithm/rule-set version; tests must cover every boundary. A result supports review and never performs an academic approval, rejection, plagiarism judgment, scope approval, or completion decision.

## Discovery

### Normalization and candidates

- Normalize Unicode and case while preserving technical acronyms and identifiers.
- Remove curated English/Filipino stop words and expand a versioned bilingual concept map such as `baha <-> flood`.
- Union the top 50 MySQL full-text candidates, top 50 local-embedding cosine candidates, and exact normalized identifier matches.
- Rank at most the top 10 for review, retaining every field/component explanation.
- Exact institutional code, DOI, repository identifier, or normalized title creates an exact-match review signal; it is not by itself a duplicate judgment.

For each comparable text field:

```text
field score = 0.50 * embedding cosine
            + 0.35 * TF-IDF cosine
            + 0.15 * controlled-concept Jaccard
```

If the embedding provider is unavailable, its contribution remains zero, the weights remain unchanged, and the run status is `PARTIAL`.

Problem score weights:

| Field | Weight |
|---|---:|
| Title | 15% |
| Problem statement | 30% |
| Objectives | 25% |
| Domain/keywords | 10% |
| Stakeholders | 10% |
| Site/context | 10% |

Solution score weights:

| Field | Weight |
|---|---:|
| Features/deliverables | 35% |
| Methodology | 25% |
| Data sources | 15% |
| Technology/system type | 15% |
| Intended users | 10% |

Objective-list similarity uses maximum-weight one-to-one matching. One prior objective cannot satisfy several proposed objectives. Confidence is the percentage of the configured field weight for which both sides contain comparable evidence.

Similarity bands are weak below 45, related at 45–64, strong overlap at 65–79, and very strong overlap at 80–100.

`POSSIBLE_DUPLICATE` requires all of:

- Problem score at least 80.
- Objective overlap at least 75.
- Solution score at least 70.
- Fewer than 25% of proposed objectives classified novel.
- Confidence at least 75%.

Sparse inputs lower confidence and may return `REVIEW_REQUIRED`; missing data cannot be treated as evidence of similarity or novelty.

## Route recommendation

Apply gates in this order:

1. Missing required intake evidence: `REVIEW_REQUIRED` with a concrete revision checklist.
2. All duplicate criteria met: `POSSIBLE_DUPLICATE` for human review.
3. `CONTINUE`: predecessor is incomplete/suspended, problem score is at least 65, at least 60% of proposed objectives link to open continuation items, and code/data access is confirmed.
4. `IMPROVE`: predecessor is complete, problem score is at least 65, documented limitation/recommendation exists, and every claimed improvement has a baseline, target, and evaluation method.
5. `NEW`: no candidate reaches 65 problem similarity, or at least 40% of objectives are novel and a distinct gap, context, beneficiary, method, or outcome is documented.
6. Anything ambiguous: `REVIEW_REQUIRED`.

Approved continue/improve routes have exactly one primary predecessor plus any number of secondary references.

## Alignment and readiness

The rule engine reports at least:

- Problem without objective.
- Objective without requirement.
- Requirement without objective, acceptance criteria, or verification method.
- Functional requirement without a realizing feature.
- Feature without an active approved requirement (`UNJUSTIFIED_FEATURE`).
- Test without a requirement or feature target.
- Required test without current execution evidence.
- Output without a path to an objective.
- Link to an obsolete, rejected, or superseded revision.
- Evidence whose requirement, test, feature, baseline, or build changed.

Requirement readiness totals 100:

| Evidence | Points |
|---|---:|
| Source objective and rationale | 20 |
| Actor/system, behavior, and outcome | 20 |
| Measurable acceptance criteria and verification method | 35 |
| Type and priority | 10 |
| Relevant data/error/interface/security/performance detail | 15 |

85–100 is baseline-ready, 70–84 needs refinement, and below 70 is incomplete. Missing source objective, acceptance criteria, verification method, or a measurable non-functional threshold remains a blocker regardless of numeric score. Phrases such as “fast,” “user-friendly,” “etc.,” and compound `and/or` produce warnings rather than automatic rejection.

## Verification and completion

Coverage is calculated over unique requirements, never raw test count:

- Mapped coverage.
- Current-version executed coverage.
- Current passing coverage.
- Priority-weighted passing coverage using Must `5`, Should `3`, Could `1`.

A requirement is verified only when every mandatory linked test is current, passing, and has evidence. Duplicate test links do not add weight.

Completion requires 100% of Must requirements mapped/executed/passing, at least 90% priority-weighted passing coverage, output evidence for every objective, no open critical finding, valid accepted exceptions, a complete continuity package, and confirmed code/data access instructions.

## Scope risk

Without an approved baseline, scope is `UNASSESSED`.

```text
Scope Risk = Governance (0..35)
           + Alignment (0..25)
           + Controlled Growth (0..20)
           + Boundary (0..20)
```

- Governance measures unapproved priority-weighted scope growth.
- Alignment measures the proportion of untraced requirements and features.
- Controlled Growth measures approved expansion from baseline 1.
- Boundary adds five points for each unresolved new stakeholder, site, integration, sensitive-data class, or critical dependency.

The result is capped at 100. Bands are Low 0–24, Moderate 25–49, High 50–74, and Critical 75–100. An unapproved objective or Must requirement creates a High floor. An unapproved sensitive-data or security expansion creates a Critical floor. Approved expansion is “controlled growth pressure,” never scope creep.

## Change impact

Perform cycle-safe breadth-first traversal across typed trace links. Return every directly or indirectly impacted artifact once, its explaining path, severity, stale tests/evidence, and documents to revise. If several paths exist, retain a shortest path and deterministic tie-breaker. A request whose `base_baseline_id` is no longer current must be recalculated before approval.

Approval and stale-evidence changes occur in one transaction: create the next immutable baseline, update the project pointer, mark affected executions/evidence stale, and append an audit event.

## Health and continuation

Health exposes Alignment, Requirement Readiness, Verification, Scope Stability, and Continuity Readiness separately. Each is Healthy 85–100, Watch 70–84, At Risk 50–69, Critical below 50, or `UNASSESSED`. The overall stage is the worst applicable dimension. Any open critical finding or failed/unverified Must requirement during validation caps the result at Critical.

Continuation readiness totals 100:

| Evidence | Points |
|---|---:|
| Trace and baseline history | 20 |
| Final documents and outputs | 15 |
| Repository commit/tag, setup, licence/access | 20 |
| Test and evidence snapshot | 15 |
| Limitations, recommendations, unfinished work, known issues | 15 |
| Ownership, data access, and contact path | 15 |

Missing code/data rights prevents “Ready.” Lineage permits forks and secondary references but rejects self-links and ancestor cycles. A successor claim appends an outcome to the new project and never mutates the predecessor item.

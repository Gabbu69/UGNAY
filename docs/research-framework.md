# Integrated Research Framework

UGNAY is both a working research-continuity web application and a controlled experimental platform. Its academic contribution is not the number of screens. It is the reproducible connection among a small interpreted research language, an evidence-preserving historical warehouse, separately runnable retrieval algorithms, and a web interface that exposes the resulting evidence without replacing human academic judgment.

```text
UGNAY RQL statement
  -> tokenizer -> parser -> AST -> semantic validator -> typed execution plan
  -> latest published warehouse snapshot (or an explicitly reported live-catalogue fallback)
  -> selected retrieval strategy and version
  -> authorized, explained web results

Operational catalogue and completion evidence
  -> Collect -> Validate -> Clean -> Transform -> Store -> Analyze
  -> immutable warehouse snapshot
  -> historical analytics and interpreter input

Frozen corpus + structured query set + adjudicated qrels
  -> identical four-arm retrieval run
  -> persisted rankings, metrics, latency, resource, configuration, and environment evidence
  -> human interpretation
```

## Subject-area mapping

| Computer Science area | Evidence in one system |
|---|---|
| Web Development | Java 21/Spring Boot API, React 19 interface, MySQL persistence, server-side sessions, CSRF, RBAC, ETags, responsive research workflows, exports, audit, and offline Windows Lite deployment |
| Data Mining | `LEXICAL_KEYWORD_V1`, `TF_IDF_COSINE_V1`, `SEMANTIC_E5_V1`, and `HYBRID_V1_1` as distinct retrieval arms with deterministic ranking and a common evaluation protocol |
| Data Warehousing | Load/stage/quality ledgers, immutable snapshots, dimensions, bridges, and study/retrieval/continuation facts built by a six-stage pipeline |
| Compiler and Interpreter | Hand-written lexer and recursive-descent parser, source-spanned AST, grammar/type/context validation, typed non-SQL plan, bounded interpreter, diagnostics, and optional processing trace |

The platform continues to support intake, adviser/coordinator routing, traceability, change analysis, verification, completion, and continuation. The Research Laboratory extends those workflows; it does not rebuild or bypass them.

## Reproducible retrieval experiment

### 1. Freeze one corpus

A curator creates a draft dataset version from selected catalogue study IDs, or the current catalogue when no selection is supplied. UGNAY copies each study's structured metadata/profile into `evaluation_corpus_items`, records its profile SHA-256, fixes item order, and hashes the corpus. Later edits to operational catalogue rows do not change this experimental version.

The maximum corpus size is 10,000 studies. A dataset cannot be empty.

### 2. Author one structured query set

Each query has a stable external key, `DEV` or `TEST` split, title, and optional problem, objectives, solution, methodology, data-source, technology, intended-user, stakeholder, and site-context fields. The complete structured snapshot and query SHA-256 are persisted. A dataset supports at most 500 queries.

Every retrieval arm receives the same combined query profile. Hybrid retrieval additionally uses the frozen field structure recorded in that same snapshot; it does not receive a different proposal.

### 3. Establish ground truth independently

Advisers or coordinators record append-only relevance-judgment revisions on the 0-3 scale:

| Grade | Interpretation for evaluation |
|---:|---|
| 0 | Not relevant |
| 1 | Marginally relevant |
| 2 | Relevant |
| 3 | Highly relevant |

Two distinct reviewers must supply current judgments for a query-study pair before a coordinator can adjudicate it into a qrel. If either reviewer later revises a judgment, the coordinator must re-adjudicate. Freeze is rejected unless every query has at least one adjudicated relevant study and every adjudicated pair retains two independent current judgments. Relevant means grade at least 1.

These qrels are human evidence, not algorithm output.

### 4. Freeze the manifest

Coordinator freeze changes the dataset version from `DRAFT` to `FROZEN` and records a dataset SHA-256 over the fixed corpus hash, query hashes/splits, qrel grades/revisions, relevance threshold, cutoffs, and freeze time. Judgments, qrels, and queries cannot be mutated after freeze.

### 5. Run the same four arms

Starting a comparison returns `202 Accepted`. The durable worker requeues interrupted `QUEUED`/`RUNNING` work after restart and evaluates these fixed arms:

| Arm | Versioned method |
|---|---|
| Lexical | `LEXICAL_KEYWORD_V1`: normalized distinct-query-token coverage baseline |
| TF-IDF | `TF_IDF_COSINE_V1`: raw term frequency, `ln((N+1)/(df+1))+1` inverse document frequency, cosine similarity |
| Semantic | `SEMANTIC_E5_V1`: local multilingual E5 cosine with `query:` and `passage:` prefixes |
| Hybrid | `HYBRID_V1_1`: 50% semantic + 35% TF-IDF + 15% controlled-concept evidence, retaining the production problem/solution field weights |

BM25 is deliberately not an arm in this release; TF-IDF supplies the requested corpus-aware lexical comparison without introducing another unvalidated implementation. Exact-title or identifier safety signals remain part of production discovery and are excluded from the pure four-arm comparison.

Every ranking uses study UUID ascending as the deterministic tie-break. Evaluation retains only the first ten hits because the declared cutoffs end at ten. A run records one warm-up and five timed repetitions, a deterministic seed, code build, algorithm configuration hashes, model/provider status, database/JVM/OS/CPU/core/max-heap details, cache policy, and environment/run hashes.

### 6. Calculate and retain metrics

Metrics are calculated at `K = {1, 3, 5, 10}`; `K=5` is primary.

- `Precision@K = relevant retrieved in top K / K`
- `Recall@K = relevant retrieved in top K / all adjudicated relevant studies`
- `F1@K` is the harmonic mean of Precision@K and Recall@K.
- `MRR@K` is the reciprocal rank of the first relevant result within K, or zero when none is retrieved.
- `NDCG@K` uses graded gain `2^grade - 1` and logarithmic rank discount.

Unjudged retrieved studies count as non-relevant. Queries without a positive qrel are rejected at dataset freeze; if an unavailable computation still produces no eligible metric, the persisted value is `UNAVAILABLE`, not zero. Aggregate rows macro-average eligible queries and persist excluded-query counts.

Index/profile build time is reported separately from query latency. The report includes p50/p95 wall latency plus heap-before/peak/after and process-CPU deltas when the JVM/OS exposes them. Missing resource measurements remain null/`UNAVAILABLE`.

### 7. Interpret, do not automate decisions

The report can be `COMPARABLE`, `PARTIAL`, or `UNAVAILABLE`. If the local semantic provider cannot produce required embeddings, the semantic arm is `UNAVAILABLE`; the hybrid arm is `PARTIAL` with a zero semantic contribution and no weight rescaling. The suite is not labelled fully comparable in that state.

Evaluation never selects the production algorithm, updates a proposal route, approves work, declares plagiarism, or certifies duplication. Recall@5 >= 0.85, NDCG@5 >= 0.75, and possible-duplicate false-positive rate <= 10% remain thesis targets only. No licensed institutional corpus/qrels are bundled, so these values remain `UNASSESSED` until a real frozen dataset is reviewed and run. Duplicate false-positive rate is a separate human-adjudicated route-classification measure; it is not fabricated from retrieval qrels and is not part of the current four-arm report.

## Roles and public interfaces

All routes below use the existing authenticated same-origin session. Mutations also require CSRF protection.

| Capability | Roles | API |
|---|---|---|
| Execute bounded RQL and view permitted analytics | Any authenticated role | `GET /research-queries/grammar`, `POST /research-queries/execute`, `GET /warehouse/analytics`, `GET /warehouse/continuation-history` |
| Create corpus/query evidence | Curator | `POST /evaluation/datasets`, `POST /evaluation/datasets/{versionId}/queries`, query import |
| Supply independent relevance judgments | Adviser, Coordinator | `POST /evaluation/queries/{queryId}/judgments` |
| Adjudicate qrels and freeze dataset | Coordinator | `POST /evaluation/queries/{queryId}/qrels`, `POST /evaluation/datasets/{versionId}/freeze` |
| Start and inspect evaluation runs | Adviser, Coordinator, Curator | `POST /evaluation/runs`, run status/report/CSV endpoints |
| Refresh and inspect warehouse loads/quality | Curator | `POST /warehouse/refresh`, load and quality endpoints |
| View/export scoped warehouse analysis | Any authenticated role | analytics and continuation JSON/CSV endpoints |

Non-curators do not receive evaluation ranked-hit IDs in the JSON report. Warehouse aggregates are visibility- and department-scoped; continuation rows tied to a successor project require explicit project membership. RQL thesis/proposal contexts are separately authorized, and unauthorized restricted or embargoed studies are removed from the candidate set before ranking so their fields or counts cannot leak. Curators retain the explicitly authorized full-corpus view.

## Research Laboratory

The sidebar exposes three route-lazy, deep-linkable workbenches:

- `/research-lab/query` enters RQL, shows stage-specific diagnostics and results, and can reveal tokens, AST, validation, and interpreted action for defense demonstration.
- `/research-lab/evaluation` shows dataset hashes/status, review completeness, durable run state, four-arm metrics, environment/parameter evidence, and CSV export. Empty real evidence remains `UNASSESSED`.
- `/research-lab/warehouse` shows the six-stage pipeline, snapshot/as-of metadata, quality state, year/department/topic trends, continuation history, and CSV exports. Tables remain authoritative; charts are secondary views of the same rows.

See [UGNAY RQL](research-query-language.md), [architecture](architecture.md), [data dictionary](data-dictionary.md), and [acceptance and demo](acceptance-and-demo.md).

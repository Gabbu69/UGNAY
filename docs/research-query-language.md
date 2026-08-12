# UGNAY Research Query Language

UGNAY RQL is a small, purpose-built language for retrieving historical research. It is not SQL and is not intended to become a general programming language. Language version `ugnay-rql-1.0.0` demonstrates the complete compiler/interpreter pipeline while keeping execution understandable and safe for an undergraduate thesis.

## Grammar

Keywords are case-insensitive. Strings are double quoted and support only `\"` and `\\` escapes.

```ebnf
query       ::= FIND target [context] [WHERE expression]
                [USING algorithm]
                [ORDER BY sortKey [direction]]
                [LIMIT integer] EOF ;

target      ::= THESIS | RELATED ;
context     ::= TO PROPOSAL string
              | TO THESIS string
              | TO TEXT string ;

expression  ::= orExpr ;
orExpr      ::= andExpr (OR andExpr)* ;
andExpr     ::= primary (AND primary)* ;
primary     ::= "(" expression ")" | predicate ;
predicate   ::= field comparator value ;

field       ::= TOPIC | TITLE | KEYWORD | YEAR | DEPARTMENT
              | METHODOLOGY | RESEARCH_AREA | STATUS | SIMILARITY ;
comparator  ::= "=" | "!=" | ">" | ">=" | "<" | "<=" | CONTAINS ;
algorithm   ::= LEXICAL | TFIDF | SEMANTIC | HYBRID ;
sortKey     ::= RELEVANCE | SIMILARITY | YEAR | TITLE ;
direction   ::= ASC | DESC ;
value       ::= string | number ;
```

`AND` binds more tightly than `OR`; parentheses make grouping explicit.

## Examples

```text
FIND THESIS WHERE TOPIC = "agriculture" AND YEAR >= 2022 ORDER BY RELEVANCE

FIND RELATED TO TEXT "offline flood warning for campuses"
WHERE SIMILARITY > 70 USING HYBRID

FIND RELATED TO THESIS "CS-2024-018"
WHERE RESEARCH_AREA = "Disaster Risk Reduction"
ORDER BY SIMILARITY DESC LIMIT 10
```

The shorthand below is valid only when the request body also supplies an authorized `selectedProposalId`:

```text
FIND RELATED WHERE SIMILARITY > 70
```

Without a target, semantic validation returns `SEM_RELATED_CONTEXT_REQUIRED` with a readable explanation.

## Processing pipeline

1. **Lexer/tokenizer.** Converts source characters into keyword, string, number, comparator, parenthesis, and EOF tokens. Every token carries zero-based offsets and one-based line/column source spans.
2. **Parser.** A hand-written recursive-descent parser validates grammar and precedence and builds a sealed AST (`Query`, `Context`, logical/group/predicate expressions, and typed literals).
3. **Grammar validation.** Unexpected targets, fields, values, clauses, and trailing input return stable parser codes plus expected tokens.
4. **Semantic validation.** Checks field types, comparator compatibility, year and similarity ranges, target/context rules, limit, sort compatibility, AST depth, and active `RESEARCH_AREA` taxonomy membership.
5. **Typed plan.** The AST becomes a `QueryPlan` containing only enum-valued target, algorithm, sort, direction, typed predicates, authorized context, and bounded limit. It has no SQL field or escape hatch.
6. **Interpreter/executor.** Fixed repository statements load at most 10,000 candidates from the latest published warehouse snapshot. If none exists, the response explicitly reports `warehouse.status=UNAVAILABLE` and uses the authoritative live catalogue. Bound values, allow-listed operations, a four-second database timeout, and a five-second overall execution deadline constrain work.
7. **Result.** The chosen versioned scorer ranks permitted evidence, deterministic UUID tie-breaking resolves equal values, and the API returns explained results, status, warehouse reference, diagnostics, and latency.

The optional browser trace displays tokens, AST, completed validation stage, typed interpreted action, algorithm version, and final results. It is educational visibility into the same execution path, not a separate simulated compiler.

## Semantic rules and defaults

- `TOPIC`, `TITLE`, `KEYWORD`, `DEPARTMENT`, `METHODOLOGY`, `RESEARCH_AREA`, and `STATUS` require a quoted non-empty string and support only `=`, `!=`, and `CONTAINS`.
- `YEAR` requires a four-digit whole number and accepts numeric comparators.
- `SIMILARITY` requires a number from 0 through 100 and is valid only for `FIND RELATED`.
- `ORDER BY SIMILARITY` is valid only for `FIND RELATED`.
- `RESEARCH_AREA` values must match an active curated taxonomy term; UGNAY does not infer or invent a research area for this filter.
- The default algorithm is `HYBRID`. Algorithm versions are `LEXICAL_KEYWORD_V1`, `TF_IDF_COSINE_V1`, `SEMANTIC_E5_V1`, and `HYBRID_V1_1`.
- The default sort is descending relevance for `THESIS` and descending similarity for `RELATED`; title defaults ascending.
- The default result limit is 20 and the maximum is 100.

## Limits and diagnostics

| Bound | Limit |
|---|---:|
| Source length | 4,096 characters |
| Tokens | 256 |
| AST depth | 16 levels |
| Candidate pool | 10,000 studies |
| Results | 100 maximum |
| Overall execution | 5 seconds |

Each diagnostic contains `stage` (`LEXER`, `PARSER`, `SEMANTIC`, or `EXECUTION`), stable `code`, readable `message`, `span`, and an `expected` token list when useful. A valid HTTP request returns HTTP 200 even when the entered language is invalid; clients inspect `valid`, `status`, `validation`, and `diagnostics`.

Payload status is one of `EXECUTED`, `PARTIAL`, `UNAVAILABLE`, `INVALID`, `TIMED_OUT`, or `FAILED`. Evidence assessment is separately `ASSESSED`, `PARTIAL`, `UNAVAILABLE`, or `UNASSESSED`; for example, an executable query with no retrieval text can be `EXECUTED` while its scores remain `UNASSESSED`.

## Safety and authorization

- The lexer rejects semicolons, comments, wildcards, SQL-only keywords, unsupported characters, negative literals, and unknown language identifiers.
- The interpreter never accepts or emits user-provided SQL, column names, operators, or sort fragments. Repository statements are fixed and external values are JDBC-bound parameters.
- All execution requires an authenticated server session and CSRF token. No token or credential is stored in browser storage.
- A proposal context requires the actor's department and, once linked to a project, explicit project membership. Curator authority remains separate. Missing, ambiguous, or unauthorized contexts intentionally return the same unavailable diagnostic.
- A restricted or embargoed study is excluded from an unauthorized viewer's candidate set before ranking. This prevents protected fields, matched terms, explanations, and result counts from leaking; a curator retains the explicitly authorized full-corpus view.
- Audit evidence stores actor, query SHA-256, action, algorithm version, warehouse reference, assessment, result/restricted counts, latency, and diagnostic codes. It does not store raw query text.

RQL retrieves and explains research evidence only. It cannot approve a thesis, declare plagiarism, certify duplication, bypass a coordinator, or change a `NEW`, `IMPROVE`, or `CONTINUE` route.

The live grammar and limits are also available from `GET /api/v1/research-queries/grammar`; execute with `POST /api/v1/research-queries/execute`.

# AGENTS.md - UGNAY Engineering Handoff

Read this file before changing the repository. It is the operational contract for humans and coding agents.

## Product boundary

UGNAY is an evidence-centred research-continuity system for software-oriented university capstones. Its canonical chain is:

`Problem -> Related studies -> Human route -> Objectives -> Requirements -> Features -> Tests -> Outputs -> Continuity package -> Successor`

Do not turn it into a generic CRUD repository, Kanban board, chat app, grading tool, file manager, or AI chatbot. The engine explains; advisers and coordinators decide. Missing evidence must be `UNASSESSED`, `PARTIAL`, empty, or unavailable. Never insert plausible demo values into a live response.

## Supported runtime

- Primary low-memory runtime: 64-bit Windows 10/11, Java 21, MySQL 8.4, Spring profile `lite`.
- One-time install: `powershell -ExecutionPolicy Bypass -File .\scripts\windows\setup-lite.ps1`.
- Later use: `Start UGNAY.cmd` and `Stop UGNAY.cmd`.
- Runtime, MySQL data, documents, logs, DPAPI-protected credentials, and backups live in `%LOCALAPPDATA%\UGNAY`, never in the Git checkout.
- Docker Compose with MinIO and ClamAV remains optional for stronger machines. XAMPP and Vercel are outside the product.

## Repository map

- `backend/`: Java 21 Spring modular monolith, REST API, Flyway, deterministic analysis, local ONNX inference.
- `frontend/`: React 19 + TypeScript research-studio interface.
- `backend/src/main/resources/db/migration/common/`: append-only cross-database Flyway migrations.
- `scripts/windows/`: lite setup, start, stop, backup, restore, diagnostics, and safe update.
- `infra/windows-lite/runtime-manifest.json`: checksummed public release dependencies.
- `docs/`: architecture, decision rules, data dictionary, acceptance, and security notes.

## Non-negotiable data rules

1. MySQL is authoritative. Do not add new in-memory source-of-truth collections.
2. Add schema changes through a new Flyway migration; never edit an already released migration.
3. Approved baselines, decisions, adviser recommendations, claim outcomes, and audit events are append-only.
4. Every project-scoped read and write must enforce department visibility plus explicit project membership. Curator authority remains separate.
5. Every baseline-bound mutation requires a concrete `If-Match`; missing or wildcard values return 428, stale values return 412.
6. Sessions are server-side and CSRF-protected. Do not put JWTs or credentials in browser storage.
7. Restricted studies may affect a score, but the server must redact protected text before serialization.
8. Completion creates exactly one catalogue study through `source_project_id` and is repeat-safe.
9. Similarity cannot automatically reject, approve, declare plagiarism, or certify duplication.

## UI rules

- Preserve the midnight shell, warm paper surfaces, editorial headings, teal/violet relationship paths, and restrained motion.
- Bind authoritative screens only to selected persisted records. The visible source and assessment state must remain honest.
- Use `/projects/:projectId/alignment`, `/projects/:projectId/changes`, `/projects/:projectId/continuity`, and `/projects/:projectId/reviews` for project work.
- Graphs require list/matrix alternatives, reduced-motion support, and keyboard-accessible controls.
- Do not add tiny functional text, decorative disabled buttons, stock dashboard cards, or neon AI styling.
- Keep Cytoscape/ECharts route-lazy; phones default to readable lists or matrices.

## Development and verification

Use Java 21. On Windows, `JAVA_HOME` must point to a JDK, not the portable runtime JRE used by end users.

```powershell
Set-Location frontend
npm ci
npm test -- --run
npm run lint
npm run build

Set-Location ..\backend
.\mvnw.cmd test
```

Build the same-origin release JAR:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\scripts\build-release.ps1
```

Before handing off, also run `scripts\verify.ps1`, parse every Windows script with the PowerShell parser, validate OpenAPI/JSON, and exercise login plus one project-scoped mutation in a browser. Report skipped Docker/MySQL/physical-4-GB checks as skipped, never passed.

## Release workflow

Release assets are public GitHub release attachments, not Git blobs. Never commit JARs, Java/MySQL archives, model weights, runtime data, backups, imported PDFs, secrets, or `.env` files.

1. Run all tests and `scripts\build-release.ps1`.
2. Exercise the INT8 model test with `-Dugnay.test.int8-model` and `-Dugnay.test.tokenizer`.
3. Update the release asset hashes in `infra/windows-lite/runtime-manifest.json`.
4. Commit and push source first.
5. Publish the JAR, INT8 ONNX model, tokenizer ZIP, and checksum manifest on the matching `vX.Y.Z` GitHub release.
6. Test a clean `setup-lite.ps1` install and an offline restart.

## If you are another AI continuing this work

Start with `git status -sb`, `git log -1 --oneline`, this file, and `README.md`. Do not assume a roadmap item is implemented from documentation alone: find its endpoint, persistence code, migration, UI binding, and tests. Preserve unrelated user changes. Keep changes small, run the focused test first, then all verification gates. If a physical Pentium/4 GB result has not been measured on actual hardware, keep it labelled as an acceptance target.

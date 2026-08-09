# Security and Operations

## Trust boundaries

UGNAY stores unpublished proposals, restricted research, project evidence, repository references, and personal account information. Treat database dumps, object backups, logs, extracted text, embeddings, and administrator sessions as sensitive university data.

The pilot has four boundaries:

- The browser is untrusted and receives only authorized API responses.
- Spring Boot is the authorization and audit boundary.
- MySQL and the selected document/scanner adapters are private infrastructure services.
- The ONNX model is local deployment data; no project text is sent to its source repository at inference time.

Accounts, Argon2id credentials, roles, invitations, explicit project memberships, and sessions are persisted in MySQL. The browser account panel has authenticated, anonymous, and unavailable states plus a curator access desk for issuing invitations and granting selected-project membership. The raw single-use token must still be delivered out of band and accepted through the public API. Automated delivery, password reset, account disablement, and global role reassignment are not implemented.

## Production acceptance controls

- Keep registration invite-only. The implemented raw invitation token is random, appears only in its creation response, is stored as a SHA-256 hash, expires after 72 hours, and is single-use; operators must deliver it through an approved out-of-band channel.
- Hash passwords with Argon2id; never encrypt or log them.
- Use server-side sessions with HttpOnly, SameSite cookies. Set `UGNAY_COOKIE_SECURE=true` whenever TLS is enabled.
- Keep CSRF protection enabled for all session-authenticated mutations.
- Authorize by institutional role, department visibility, project membership, and record state—not by a UI control alone.
- Log decisions, overrides, role changes, exports, restricted-document access, accepted findings, baseline approvals, and completion.
- Redact secrets, session identifiers, proposal text, and document contents from logs.
- Rate-limit login, invitation acceptance, uploads, discovery runs, and expensive analysis actions.

## Upload path

1. Permit `POST /api/v1/imports/documents` only for a curator and reject oversized bodies before parsing.
2. Compare reported MIME type, detected MIME type, and the `%PDF-` file signature; do not trust the filename.
3. Stage the upload under a randomized temporary name and scan it with Windows Defender in Lite or ClamAV in Compose. A timeout or unavailable scanner is a failed-closed result, not clean.
4. Before object storage, persist the restricted document/version and extraction row in `VALIDATING` state with SHA-256, size, uploader, visibility, scan result, extractor version, and limits.
5. Store the clean object under a randomized private key, then atomically record its storage ETag, expose storage state `STORED`, and move extraction state to `QUEUED`. Respond `202 Accepted` only after both private storage and queued state are confirmed. The body contains `jobId`, `documentId`, `documentVersionId`, `status`, `statusUrl`, `eventsUrl`, and `queuedAt`; `Location` names the status URL.
6. Run Tika asynchronously with character/time limits. A timeout or interrupt cancels the parser future, calls `shutdownNow()` on its virtual-thread executor, and records a non-success extraction state. Treat extracted text as untrusted data.
7. Keep `GET /api/v1/imports/documents/jobs/{jobId}` as the durable state. The curator-only `/events` SSE stream sends progress but is not a substitute for polling/reconciliation after disconnection.
8. On restart, requeue jobs interrupted while running and mark uncertain pre-storage validations `ORPHAN_REVIEW`. Do not make failed, low-text, or manually reviewed extraction automatically publication-eligible.
9. Authorize every download and use safe `Content-Disposition` plus `X-Content-Type-Options: nosniff`.

Scanned PDFs are not OCR'd in v1. Low-text extraction creates a manual-metadata finding and must not be represented as a complete search profile.

## Secrets and network exposure

- Lite stores generated credentials with Windows DPAPI and applies current-user/SYSTEM ACLs to `%LOCALAPPDATA%\UGNAY`. Compose `.env` remains ignored by Git.
- MySQL and the MinIO console bind to `127.0.0.1`; never expose MySQL, MinIO API/console, or ClamAV directly to the campus network.
- MinIO root credentials initialize, restore, and back up storage. The application uses a separate access key.
- `minio-init` creates a dedicated application user with a custom policy scoped to the configured document bucket. It allows the required list/read/write and multipart operations but intentionally omits `s3:DeleteObject`; destructive retention and recovery work therefore remains an explicit operator action. Do not add administrative permissions to that account.
- Choose the final bootstrap password before first start, and rotate the MySQL application/root and MinIO root/application passwords before importing real data. Changing the bootstrap environment variable does not update an existing credential.
- Container images use constrained service networks and `no-new-privileges`. The app has a read-only filesystem and a bounded temporary filesystem. Only ClamAV joins the separate `signature-egress` network so FreshClam can update signatures; MySQL, MinIO, and storage tooling remain on the internal backend network.
- Review and pin image digests during a release freeze; floating patch tags are appropriate for development but not reproducible evidence.

## Windows Lite operations

The supported low-memory setup is `scripts\windows\setup-lite.ps1`. It checksum-verifies every portable dependency, binds MySQL on `127.0.0.1:3307`, protects credentials with DPAPI, and keeps runtime/data/documents/logs/backups outside the clone. Use the root Start/Stop/Backup/Update launchers. The updater makes a safety backup and rolls back the commit, installed release pointer, database, and documents when migration or health verification fails. Port ownership is validated before any stop/kill action.

Lite document ingestion fails closed when Windows Defender cannot scan; discovery continues lexically when available RAM is below the semantic threshold or the INT8 model cannot load. Neither fallback is reported as a complete assessment.

## Pilot deployment checklist

1. Confirm institutional authority to process every imported PDF and its extracted text.
2. Complete a privacy classification for stakeholder names, research participants, and restricted studies.
3. Replace all example secrets and restrict `.env` to the operator account.
4. Set the final bootstrap identity before first start. Changing `.env` later does not rotate the stored password; the pilot has no password-reset endpoint.
5. Provision the pinned model with `scripts/fetch-model.ps1`, or explicitly accept `PARTIAL` lexical-only discovery.
6. Configure DNS, `UGNAY_DOMAIN`, `CADDY_ACME_EMAIL`, and `UGNAY_COOKIE_SECURE=true`.
7. Permit inbound 80/443 only. Verify stateful service ports are loopback/private.
8. Run `.\scripts\verify.ps1`, then complete the backend, frontend, Compose, authorization, account-panel login/logout, invitation, upload, and end-to-end checks. A skipped gate is unresolved evidence, not a pass. The production Docker build itself must pass the backend Maven tests and frontend typecheck/build; run frontend unit tests separately.
9. Verify restricted excerpts remain redacted in search evidence for an unauthorized role.
10. Create a backup, restore it into an isolated rehearsal stack, and document recovery time.
11. Record adviser/coordinator UAT and the bilingual ranking calibration report.

## Backup and restore runbook

### Backup

Run:

```powershell
.\scripts\backup.ps1
```

The script briefly stops a running application to prevent cross-store writes, then creates `database.sql`, `object-storage/`, `manifest.json`, and `SHA256SUMS.txt` before restarting it. The transactional dump uses `--no-tablespaces`, so the database backup identity does not need the broad MySQL `PROCESS` privilege merely to read tablespace metadata. A `.incomplete` marker remains when an operation fails. Never use an incomplete folder for recovery.

Recommended policy for a supervised pilot:

- Daily automated local backup.
- Weekly encrypted copy to institution-controlled storage in another failure domain.
- Thirty daily and twelve monthly recovery points, adjusted to university policy.
- Quarterly restore rehearsal and a restore immediately before pilot acceptance.
- Restricted access and an auditable disposal process because deletion from the live system does not delete historical backups.

### Restore

Restoring overwrites the active MySQL database and synchronizes the MinIO bucket, including removals. Verify the absolute target folder before executing:

```powershell
.\scripts\restore.ps1 `
  -BackupPath C:\secured-backups\ugnay-20260808-220000 `
  -ConfirmRestore RESTORE-UGNAY
```

The script validates SHA-256 checksums, creates a safety backup unless disabled, stops the app, restores database and objects, deletes restored Spring Session rows so old cookies cannot revive access, and restarts the app. Afterward:

1. Check `docker compose ps` and `/actuator/health`.
2. Sign in and open one catalogue PDF, one discovery run, and one project baseline.
3. Verify the most recent audit and completion records.
4. Run a fresh backup only after the restored state is accepted.

`-DatabaseOnly` intentionally allows broken document references and is for forensic recovery, not normal operations.

## Monitoring and incident handling

For the pilot, retain structured application and Caddy logs outside short-lived container output. Alert on repeated login failure, denied restricted-document access, malware detection, extraction backlog, failed analysis jobs, database/storage health, disk exhaustion, and backup failure.

If document malware or unauthorized disclosure is suspected:

1. Stop accepting affected invitations, invalidate active sessions, and contain account access at the ingress or by taking the pilot offline if necessary; the pilot has no supported account-disable action. Preserve audit evidence.
2. Quarantine the object; do not erase it before the incident owner decides evidence retention.
3. Identify every access/download event and successor record that referenced it.
4. Rotate exposed credentials and invalidate active sessions.
5. Notify the university privacy/security owner under institutional policy.
6. Restore only from a verified point and document all manual corrections as audited events.

## Known operational limitations

- Single-host Compose is not highly available.
- Logs are not centrally retained by default.
- Backups are local until an operator copies/encrypts them elsewhere.
- ClamAV detects known malware; it does not make arbitrary documents safe.
- Caddy supplies transport security, not institutional identity or policy approval.
- The model is a retrieval aid, not a plagiarism detector or academic authority.
- Semantic performance on English/Filipino proposals remains unvalidated until the pilot benchmark is completed.

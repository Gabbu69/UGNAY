package com.ugnay.platform.catalogue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Repository
public class DocumentIngestionRepository {
    private final JdbcTemplate jdbc;

    public DocumentIngestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public DocumentImportJob createValidating(PendingDocument upload) {
        byte[] uploaderId = jdbc.query("SELECT id FROM user_accounts WHERE email = ?",
                        (result, row) -> result.getBytes(1), upload.uploaderEmail().toLowerCase(Locale.ROOT))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("The authenticated uploader account no longer exists."));
        Instant queuedAt = Instant.now();
        jdbc.update("INSERT INTO documents(id, owner_type, owner_id, document_purpose, created_at) VALUES(?,?,?,?,?)",
                bytes(upload.documentId()), "IMPORT_JOB", bytes(upload.jobId()), "STUDY_SOURCE", Timestamp.from(queuedAt));
        jdbc.update("INSERT INTO document_versions(id, document_id, version_number, object_key, original_filename, mime_type, byte_size, sha256, uploaded_by, visibility, scan_status, extraction_status, created_at, storage_etag) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(upload.versionId()), bytes(upload.documentId()), 1, upload.objectKey(), upload.originalFilename(),
                upload.mimeType(), upload.byteSize(), upload.sha256(), uploaderId, "RESTRICTED", "CLEAN", "VALIDATING",
                Timestamp.from(queuedAt), null);
        jdbc.update("INSERT INTO extraction_runs(id, document_version_id, extractor_version, run_status, extracted_character_count, failure_reason, started_at, completed_at, queued_at, progress_percent, page_count, max_character_count, timeout_seconds, attempt_count, manual_review_required, publication_eligible) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bytes(upload.jobId()), bytes(upload.versionId()), upload.extractorVersion(), "VALIDATING", 0, null,
                Timestamp.from(queuedAt), null, Timestamp.from(queuedAt), 0, 0, upload.maxCharacters(), upload.timeoutSeconds(),
                0, true, false);
        return find(upload.jobId());
    }

    @Transactional(readOnly = true)
    public DocumentImportJob find(UUID jobId) {
        List<DocumentImportJob> jobs = jdbc.query("SELECT er.id AS job_id, d.id AS document_id, dv.id AS version_id, "
                        + "dv.original_filename, dv.mime_type, dv.byte_size, dv.sha256, dv.scan_status, dv.object_key, dv.storage_etag, "
                        + "er.run_status, er.progress_percent, er.page_count, er.extracted_character_count, er.max_character_count, "
                        + "er.timeout_seconds, er.attempt_count, er.manual_review_required, er.publication_eligible, er.failure_reason, "
                        + "er.queued_at, er.started_at, er.completed_at, u.email AS uploader_email "
                        + "FROM extraction_runs er JOIN document_versions dv ON dv.id = er.document_version_id "
                        + "JOIN documents d ON d.id = dv.document_id JOIN user_accounts u ON u.id = dv.uploaded_by WHERE er.id = ?",
                (result, row) -> {
                    String status = result.getString("run_status");
                    Timestamp started = result.getTimestamp("started_at");
                    Timestamp completed = result.getTimestamp("completed_at");
                    return new DocumentImportJob(uuid(result.getBytes("job_id")), uuid(result.getBytes("document_id")),
                            uuid(result.getBytes("version_id")), result.getString("original_filename"), result.getString("mime_type"),
                            result.getLong("byte_size"), result.getString("sha256"), result.getString("scan_status"), storageStatus(status),
                            result.getString("object_key"), result.getString("storage_etag"), status,
                            result.getInt("progress_percent"), result.getInt("page_count"), result.getInt("extracted_character_count"),
                            result.getInt("max_character_count"), result.getInt("timeout_seconds"), result.getInt("attempt_count"),
                            result.getBoolean("manual_review_required"), result.getBoolean("publication_eligible"),
                            result.getString("failure_reason"), preview(jobId), result.getString("uploader_email"),
                            result.getTimestamp("queued_at").toInstant(), Set.of("VALIDATING", "QUEUED").contains(status) ? null : started.toInstant(),
                            completed == null ? null : completed.toInstant());
                }, bytes(jobId));
        return jobs.stream().findFirst().orElseThrow(() -> new NoSuchElementException("Extraction job does not exist: " + jobId));
    }

    @Transactional
    public DocumentImportJob markStoredAndQueued(UUID jobId, String storageEtag) {
        DocumentImportJob current = find(jobId);
        if (!"VALIDATING".equals(current.status())) return current;
        Instant queuedAt = Instant.now();
        jdbc.update("UPDATE document_versions SET storage_etag = ?, extraction_status = 'QUEUED' WHERE id = ?",
                storageEtag, bytes(current.documentVersionId()));
        int updated = jdbc.update("UPDATE extraction_runs SET run_status = 'QUEUED', queued_at = ?, progress_percent = 0, failure_reason = NULL WHERE id = ? AND run_status = 'VALIDATING'",
                Timestamp.from(queuedAt), bytes(jobId));
        if (updated != 1) throw new IllegalStateException("The pending extraction job changed before storage could be confirmed.");
        return find(jobId);
    }

    @Transactional
    public DocumentImportJob markStorageReview(UUID jobId, String status, String storageEtag, String reason) {
        if (!Set.of("FAILED_STORAGE", "ORPHAN_REVIEW").contains(status)) {
            throw new IllegalArgumentException("Unsupported storage review status: " + status);
        }
        DocumentImportJob current = find(jobId);
        jdbc.update("UPDATE document_versions SET storage_etag = ?, extraction_status = ? WHERE id = ?",
                storageEtag, status, bytes(current.documentVersionId()));
        jdbc.update("UPDATE extraction_runs SET run_status = ?, progress_percent = 100, failure_reason = ?, completed_at = ?, manual_review_required = TRUE, publication_eligible = FALSE WHERE id = ?",
                status, abbreviate(reason), Timestamp.from(Instant.now()), bytes(jobId));
        return find(jobId);
    }

    @Transactional
    public boolean claim(UUID jobId) {
        int updated = jdbc.update("UPDATE extraction_runs SET run_status = 'RUNNING', progress_percent = 10, started_at = ?, failure_reason = NULL, attempt_count = attempt_count + 1 WHERE id = ? AND run_status = 'QUEUED'",
                Timestamp.from(Instant.now()), bytes(jobId));
        if (updated == 0) return false;
        byte[] versionId = versionId(jobId);
        jdbc.update("UPDATE document_versions SET extraction_status = 'RUNNING' WHERE id = ?", versionId);
        return true;
    }

    @Transactional
    public DocumentImportJob finish(UUID jobId, PdfTextExtractor.ExtractionOutcome outcome) {
        DocumentImportJob current = find(jobId);
        if (!"RUNNING".equals(current.status())) return current;
        jdbc.update("DELETE FROM document_segments WHERE extraction_run_id = ?", bytes(jobId));
        List<String> segments = chunks(outcome.text());
        for (int index = 0; index < segments.size(); index++) {
            jdbc.update("INSERT INTO document_segments(id, extraction_run_id, segment_order, page_number, section_label, extracted_text) VALUES(?,?,?,?,?,?)",
                    bytes(UUID.randomUUID()), bytes(jobId), index, null, "BODY", segments.get(index));
        }
        int characters = outcome.text().length();
        boolean extracted = "EXTRACTED".equals(outcome.status());
        boolean lowText = characters < 300;
        boolean manualReview = !extracted || lowText;
        boolean eligible = extracted && !lowText;
        String reason = outcome.failureReason();
        if (extracted && lowText) reason = "Fewer than 300 characters were extracted; complete metadata manually before publication.";
        jdbc.update("UPDATE extraction_runs SET run_status = ?, progress_percent = 100, page_count = ?, extracted_character_count = ?, failure_reason = ?, completed_at = ?, manual_review_required = ?, publication_eligible = ? WHERE id = ?",
                outcome.status(), outcome.pageCount(), characters, abbreviate(reason), Timestamp.from(Instant.now()), manualReview, eligible, bytes(jobId));
        jdbc.update("UPDATE document_versions SET extraction_status = ? WHERE id = ?", outcome.status(), bytes(current.documentVersionId()));
        return find(jobId);
    }

    @Transactional
    public DocumentImportJob fail(UUID jobId, String reason) {
        DocumentImportJob current = find(jobId);
        jdbc.update("UPDATE extraction_runs SET run_status = 'FAILED', progress_percent = 100, failure_reason = ?, completed_at = ?, manual_review_required = TRUE, publication_eligible = FALSE WHERE id = ?",
                abbreviate(reason), Timestamp.from(Instant.now()), bytes(jobId));
        jdbc.update("UPDATE document_versions SET extraction_status = 'FAILED' WHERE id = ?", bytes(current.documentVersionId()));
        return find(jobId);
    }

    @Transactional
    public int requeueInterruptedRuns() {
        List<JobVersion> interrupted = jdbc.query("SELECT er.id, er.document_version_id FROM extraction_runs er WHERE er.run_status = 'RUNNING'",
                (result, row) -> new JobVersion(uuid(result.getBytes(1)), uuid(result.getBytes(2))));
        for (JobVersion job : interrupted) {
            jdbc.update("UPDATE extraction_runs SET run_status = 'QUEUED', progress_percent = 0, failure_reason = 'Application restarted before extraction completed.' WHERE id = ?",
                    bytes(job.jobId()));
            jdbc.update("UPDATE document_versions SET extraction_status = 'QUEUED' WHERE id = ?", bytes(job.versionId()));
        }
        return interrupted.size();
    }

    @Transactional
    public int closeInterruptedValidations() {
        List<UUID> validations = jdbc.query("SELECT id FROM extraction_runs WHERE run_status = 'VALIDATING'",
                (result, row) -> uuid(result.getBytes(1)));
        for (UUID jobId : validations) {
            markStorageReview(jobId, "ORPHAN_REVIEW", find(jobId).storageEtag(),
                    "Application restarted before private object storage could be confirmed; curator review is required.");
        }
        return validations.size();
    }

    @Transactional(readOnly = true)
    public List<UUID> queuedJobIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT id FROM extraction_runs WHERE run_status = 'QUEUED' ORDER BY queued_at LIMIT " + safeLimit,
                (result, row) -> uuid(result.getBytes(1)));
    }

    private byte[] versionId(UUID jobId) {
        return jdbc.query("SELECT document_version_id FROM extraction_runs WHERE id = ?", (result, row) -> result.getBytes(1), bytes(jobId))
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Extraction job does not exist: " + jobId));
    }

    private String preview(UUID jobId) {
        return jdbc.query("SELECT extracted_text FROM document_segments WHERE extraction_run_id = ? ORDER BY segment_order LIMIT 1",
                        (result, row) -> result.getString(1), bytes(jobId)).stream().findFirst()
                .map(text -> text.replaceAll("\\s+", " ").strip())
                .map(text -> text.length() <= 1000 ? text : text.substring(0, 997) + "...").orElse("");
    }

    private static List<String> chunks(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + 10_000, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(text.substring(offset, end));
            offset = end;
        }
        return chunks;
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }

    private static String storageStatus(String runStatus) {
        return switch (runStatus) {
            case "VALIDATING" -> "PENDING_STORAGE";
            case "FAILED_STORAGE" -> "NOT_STORED";
            case "ORPHAN_REVIEW" -> "ORPHAN_REVIEW";
            default -> "STORED";
        };
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record PendingDocument(UUID jobId, UUID documentId, UUID versionId, String objectKey,
                                 String originalFilename, String mimeType, long byteSize, String sha256,
                                 String uploaderEmail, String extractorVersion, int maxCharacters, int timeoutSeconds) {}

    private record JobVersion(UUID jobId, UUID versionId) {}
}

package com.ugnay.platform.catalogue;

import com.ugnay.platform.catalogue.DocumentIngestionRepository.PendingDocument;
import com.ugnay.platform.shared.JdbcAuditService;
import com.ugnay.platform.shared.HeavyOperationCoordinator;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public final class DocumentIngestionService {
    private static final Set<String> ACCEPTED_DECLARED_TYPES = Set.of("application/pdf", "application/octet-stream");
    private static final String EXTRACTOR_VERSION = "tika-3.3.0";

    private final DocumentIngestionRepository repository;
    private final DocumentObjectStorage storage;
    private final MalwareScanner scanner;
    private final PdfTextExtractor extractor;
    private final DocumentJobEventStream events;
    private final JdbcAuditService audit;
    private final DocumentExtractionExecutor executor;
    private final HeavyOperationCoordinator heavyOperations;
    private final long maxFileBytes;
    private final int maxCharacters;
    private final int timeoutSeconds;
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();

    public DocumentIngestionService(DocumentIngestionRepository repository, DocumentObjectStorage storage,
            MalwareScanner scanner, PdfTextExtractor extractor, DocumentJobEventStream events, JdbcAuditService audit,
            DocumentExtractionExecutor executor, HeavyOperationCoordinator heavyOperations,
            @Value("${ugnay.ingestion.max-file-bytes:26214400}") long maxFileBytes,
            @Value("${ugnay.tika.max-characters:2000000}") int maxCharacters,
            @Value("${ugnay.tika.timeout-seconds:30}") int timeoutSeconds) {
        this.repository = repository;
        this.storage = storage;
        this.scanner = scanner;
        this.extractor = extractor;
        this.events = events;
        this.audit = audit;
        this.executor = executor;
        this.heavyOperations = heavyOperations;
        this.maxFileBytes = Math.max(1, maxFileBytes);
        this.maxCharacters = Math.max(1_000, maxCharacters);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public DocumentAccepted submit(MultipartFile file, String uploaderEmail) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("The uploaded document is empty.");
        if (file.getSize() > maxFileBytes) throw new IllegalArgumentException("The uploaded document exceeds the configured size limit.");
        String filename = safeFilename(file.getOriginalFilename());
        String declaredType = file.getContentType();
        if (declaredType != null && !ACCEPTED_DECLARED_TYPES.contains(declaredType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The declared content type is not a PDF.");
        }

        Path staged = Files.createTempFile("ugnay-upload-", ".pdf");
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(staged);
            if (size == 0) throw new IllegalArgumentException("The uploaded document is empty.");
            if (size > maxFileBytes) throw new IllegalArgumentException("The uploaded document exceeds the configured size limit.");
            if (!hasPdfSignature(staged)) throw new IllegalArgumentException("Only genuine PDF files are accepted for study ingestion.");
            String detected = detect(staged, filename);
            if (!"application/pdf".equals(detected)) {
                throw new IllegalArgumentException("Detected content type is " + detected + ", not application/pdf.");
            }
            String hash = sha256(staged);
            MalwareScanner.ScanResult scan = scanner.scan(staged);
            if (scan == MalwareScanner.ScanResult.INFECTED) {
                throw new IllegalArgumentException("ClamAV reported malware; the document was not stored or queued.");
            }
            if (scan != MalwareScanner.ScanResult.CLEAN) {
                throw new DocumentIngestionUnavailableException("ClamAV did not return a clean verdict; ingestion failed closed.");
            }

            UUID jobId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            Instant now = Instant.now();
            String objectKey = "imports/" + now.atZone(ZoneOffset.UTC).getYear() + "/"
                    + String.format("%02d", now.atZone(ZoneOffset.UTC).getMonthValue()) + "/" + UUID.randomUUID() + "/" + sanitize(filename);
            repository.createValidating(new PendingDocument(jobId, documentId, versionId, objectKey,
                    filename, detected, size, hash, uploaderEmail, EXTRACTOR_VERSION, maxCharacters, timeoutSeconds));

            DocumentObjectStorage.StoredObject stored;
            try {
                stored = storage.store(objectKey, staged, detected, Map.of(
                        "sha256", hash,
                        "original-filename", sanitize(filename),
                        "scan-status", "CLEAN"));
            } catch (IOException exception) {
                preserveStorageReview(jobId, null,
                        "Private object storage did not confirm the upload; curator orphan review is required.", exception);
                throw new DocumentIngestionUnavailableException(
                        "The validated document could not be stored privately: " + safeMessage(exception), exception);
            }

            DocumentImportJob queued;
            try {
                queued = repository.markStoredAndQueued(jobId, stored.etag());
            } catch (RuntimeException exception) {
                preserveStorageReview(jobId, stored.etag(),
                        "The object was stored but its queued state could not be confirmed; curator orphan review is required.", exception);
                throw exception;
            }
            dispatch(jobId);
            audit.append(uploaderEmail, "DOCUMENT_EXTRACTION_QUEUED", "DOCUMENT_VERSION", versionId,
                    "Validated, malware-scanned, and privately stored a PDF before queuing extraction.",
                    Map.of("jobId", jobId.toString(), "sha256", hash, "byteSize", size));
            return new DocumentAccepted(jobId, documentId, versionId, queued.status(),
                    "/api/v1/imports/documents/jobs/" + jobId,
                    "/api/v1/imports/documents/jobs/" + jobId + "/events", queued.queuedAt());
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public DocumentImportJob job(UUID jobId) {
        return repository.find(jobId);
    }

    public SseEmitter events(UUID jobId) throws IOException {
        return events.subscribe(jobId, () -> job(jobId));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeDurableJobs() {
        repository.closeInterruptedValidations();
        repository.requeueInterruptedRuns();
        dispatchBacklog();
    }

    private void dispatch(UUID jobId) {
        if (!scheduled.add(jobId)) return;
        try {
            executor.execute(() -> {
                try { extract(jobId); }
                finally {
                    scheduled.remove(jobId);
                    dispatchBacklog();
                }
            });
        } catch (RejectedExecutionException ignored) {
            scheduled.remove(jobId);
            // The bounded queue is full. This job remains durably QUEUED and is picked up as workers finish.
        }
    }

    private void extract(UUID jobId) {
        var lease = heavyOperations.acquire("PDF_EXTRACTION");
        if (lease.isEmpty()) return;
        try (var ignored = lease.orElseThrow()) {
            if (!repository.claim(jobId)) return;
            DocumentImportJob running = repository.find(jobId);
            events.publish(running);
            DocumentImportJob finished;
            try (InputStream source = storage.open(running.objectKey())) {
                PdfTextExtractor.ExtractionOutcome outcome = extractor.extract(source, running.originalFilename(),
                        running.maxCharacterCount(), running.timeoutSeconds());
                finished = repository.finish(jobId, normalize(outcome));
            } catch (Exception exception) {
                DocumentImportJob failed = repository.fail(jobId, "Stored PDF extraction failed: " + safeMessage(exception));
                events.publish(failed);
                audit.append(failed.uploaderEmail(), "DOCUMENT_EXTRACTION_FAILED", "DOCUMENT_VERSION", failed.documentVersionId(),
                        "Asynchronous PDF extraction failed and requires curator review.",
                        Map.of("jobId", jobId.toString(), "reason", safeMessage(exception)));
                return;
            }
            events.publish(finished);
            audit.append(finished.uploaderEmail(), "DOCUMENT_EXTRACTION_FINISHED", "DOCUMENT_VERSION", finished.documentVersionId(),
                    "Finished asynchronous PDF extraction without publishing a catalogue study.",
                    Map.of("jobId", jobId.toString(), "status", finished.status(),
                            "characters", finished.extractedCharacterCount(), "pages", finished.pageCount(),
                            "publicationEligible", finished.publicationEligible()));
        }
    }

    private void dispatchBacklog() {
        repository.queuedJobIds(100).forEach(this::dispatch);
    }

    private void preserveStorageReview(UUID jobId, String storageEtag, String reason, RuntimeException original) {
        try { repository.markStorageReview(jobId, "ORPHAN_REVIEW", storageEtag, reason); }
        catch (RuntimeException stateFailure) { original.addSuppressed(stateFailure); }
    }

    private void preserveStorageReview(UUID jobId, String storageEtag, String reason, IOException original) {
        try { repository.markStorageReview(jobId, "ORPHAN_REVIEW", storageEtag, reason); }
        catch (RuntimeException stateFailure) { original.addSuppressed(stateFailure); }
    }

    private static PdfTextExtractor.ExtractionOutcome normalize(PdfTextExtractor.ExtractionOutcome outcome) {
        if (outcome == null) return new PdfTextExtractor.ExtractionOutcome("FAILED", "", 0, "Extractor returned no result.");
        if (!Set.of("EXTRACTED", "CHARACTER_LIMIT_REACHED", "TIMED_OUT", "INTERRUPTED", "FAILED").contains(outcome.status())) {
            return new PdfTextExtractor.ExtractionOutcome("FAILED", "", outcome.pageCount(), "Extractor returned an unsupported status.");
        }
        return outcome;
    }

    private static boolean hasPdfSignature(Path source) throws IOException {
        byte[] signature = new byte[5];
        try (InputStream input = Files.newInputStream(source)) {
            if (input.readNBytes(signature, 0, signature.length) != signature.length) return false;
        }
        return signature[0] == '%' && signature[1] == 'P' && signature[2] == 'D' && signature[3] == 'F' && signature[4] == '-';
    }

    private static String detect(Path source, String filename) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            return new Tika().detect(input, filename);
        }
    }

    private static String sha256(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                for (int length; (length = input.read(buffer)) >= 0;) {
                    if (length > 0) digest.update(buffer, 0, length);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safeFilename(String value) {
        String filename = value == null ? "study.pdf" : value.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).strip();
        if (filename.isEmpty()) filename = "study.pdf";
        if (filename.length() > 255) throw new IllegalArgumentException("The original filename exceeds 255 characters.");
        return filename;
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(sanitized.length() - 180);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() <= 900 ? message : message.substring(0, 897) + "...";
    }

    public record DocumentAccepted(UUID jobId, UUID documentId, UUID documentVersionId, String status,
                                   String statusUrl, String eventsUrl, Instant queuedAt) {}
}

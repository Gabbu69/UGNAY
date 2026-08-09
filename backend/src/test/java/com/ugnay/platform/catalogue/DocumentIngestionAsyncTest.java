package com.ugnay.platform.catalogue;

import com.ugnay.platform.shared.JdbcAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DocumentIngestionAsyncTest.Doubles.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ugnay-ingestion-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=YEAR",
        "ugnay.ingestion.extraction-workers=1",
        "ugnay.ingestion.extraction-queue-capacity=2",
        "ugnay.tika.max-characters=50000",
        "ugnay.tika.timeout-seconds=4"
})
class DocumentIngestionAsyncTest {
    private static final byte[] PDF = ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n"
            + "This is a structurally minimal test PDF whose real text is supplied by the isolated extractor double.\n%%EOF")
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentIngestionService service;
    @Autowired TestStorage storage;
    @Autowired TestScanner scanner;
    @Autowired TestExtractor extractor;
    @Autowired JdbcAuditService audit;

    @BeforeEach
    void resetDoubles() {
        storage.reset();
        scanner.reset();
        extractor.reset(PdfTextExtractor.ExtractionOutcome.extracted("Evidence ".repeat(80), 7), true);
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void uploadReturnsAcceptedBeforeBoundedExtractionAndDurableStateSurvivesAFreshRepository() throws Exception {
        int studiesBefore = count("studies");
        var response = mvc.perform(multipart("/api/v1/imports/documents").file(pdf()).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").isNotEmpty())
                .andReturn();

        UUID jobId = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("jobId").asText());
        assertThat(extractor.started.await(3, TimeUnit.SECONDS)).isTrue();

        mvc.perform(get("/api/v1/imports/documents/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progressPercent").value(10))
                .andExpect(jsonPath("$.timeoutSeconds").value(4));

        DocumentIngestionRepository freshRepository = new DocumentIngestionRepository(jdbc);
        DocumentImportJob persistedWhileRunning = freshRepository.find(jobId);
        assertThat(persistedWhileRunning.sha256()).hasSize(64);
        assertThat(persistedWhileRunning.scanStatus()).isEqualTo("CLEAN");
        assertThat(persistedWhileRunning.storageStatus()).isEqualTo("STORED");
        assertThat(persistedWhileRunning.uploaderEmail()).isEqualTo("admin@ugnay.local");
        assertThat(storage.lastMetadata).containsEntry("sha256", persistedWhileRunning.sha256())
                .containsEntry("scan-status", "CLEAN");

        extractor.release.countDown();
        DocumentImportJob completed = awaitStatus(jobId, "EXTRACTED");
        assertThat(completed.pageCount()).isEqualTo(7);
        assertThat(completed.extractedCharacterCount()).isGreaterThan(300);
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(completed.manualReviewRequired()).isFalse();
        assertThat(completed.publicationEligible()).isTrue();
        assertThat(completed.textPreview()).contains("Evidence");
        assertThat(count("document_segments")).isGreaterThan(0);
        assertThat(count("studies")).isEqualTo(studiesBefore);
        assertThat(audit.list(500)).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("DOCUMENT_EXTRACTION_FINISHED");
            assertThat(event.actorEmail()).isEqualTo("admin@ugnay.local");
        });
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void clamAvUnavailableFailsClosedWithoutStorageOrDatabaseRows() throws Exception {
        scanner.result = MalwareScanner.ScanResult.UNAVAILABLE;
        int documentsBefore = count("documents");
        int runsBefore = count("extraction_runs");

        mvc.perform(multipart("/api/v1/imports/documents").file(pdf()).with(csrf()))
                .andExpect(status().isServiceUnavailable());

        assertThat(storage.storeCount.get()).isZero();
        assertThat(count("documents")).isEqualTo(documentsBefore);
        assertThat(count("extraction_runs")).isEqualTo(runsBefore);
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void uncertainObjectStoreFailureLeavesDurableOrphanReviewWithoutDeletePermission() throws Exception {
        storage.failStore = true;
        int runsBefore = count("extraction_runs");
        int orphanRunsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM extraction_runs WHERE run_status = 'ORPHAN_REVIEW'", Integer.class);
        int orphanVersionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM document_versions WHERE extraction_status = 'ORPHAN_REVIEW'", Integer.class);

        mvc.perform(multipart("/api/v1/imports/documents").file(pdf()).with(csrf()))
                .andExpect(status().isServiceUnavailable());

        assertThat(count("extraction_runs")).isEqualTo(runsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extraction_runs WHERE run_status = 'ORPHAN_REVIEW'", Integer.class))
                .isEqualTo(orphanRunsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_versions WHERE extraction_status = 'ORPHAN_REVIEW'", Integer.class))
                .isEqualTo(orphanVersionsBefore + 1);
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void invalidSignatureIsRejectedBeforeMalwareScanAndStorage() throws Exception {
        MockMultipartFile invalid = new MockMultipartFile("file", "not-a-pdf.pdf", "application/pdf",
                "plain text".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/imports/documents").file(invalid).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(scanner.scanCount.get()).isZero();
        assertThat(storage.storeCount.get()).isZero();
    }

    @Test
    @WithMockUser(username = "admin@ugnay.local", roles = "CURATOR")
    void timeoutRemainsStoredButCannotBecomePublicationEligible() throws Exception {
        extractor.reset(new PdfTextExtractor.ExtractionOutcome("TIMED_OUT", "", 0, "Configured timeout elapsed."), false);
        var response = mvc.perform(multipart("/api/v1/imports/documents").file(pdf()).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        UUID jobId = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("jobId").asText());
        DocumentImportJob timedOut = awaitStatus(jobId, "TIMED_OUT");
        assertThat(timedOut.manualReviewRequired()).isTrue();
        assertThat(timedOut.publicationEligible()).isFalse();
        assertThat(timedOut.failureReason()).contains("timeout");
        assertThat(timedOut.completedAt()).isNotNull();
    }

    @Test
    void freshRepositoryRecoversInterruptedRunningAndValidatingStatesWithoutInMemoryJobData() {
        DocumentIngestionRepository first = new DocumentIngestionRepository(jdbc);
        UUID runningJob = UUID.randomUUID();
        first.createValidating(pending(runningJob));
        first.markStoredAndQueued(runningJob, "recovery-etag");
        assertThat(first.claim(runningJob)).isTrue();

        DocumentIngestionRepository afterRestart = new DocumentIngestionRepository(jdbc);
        assertThat(afterRestart.requeueInterruptedRuns()).isGreaterThanOrEqualTo(1);
        assertThat(afterRestart.find(runningJob).status()).isEqualTo("QUEUED");
        afterRestart.markStorageReview(runningJob, "ORPHAN_REVIEW", "recovery-etag", "Test cleanup after recovery assertion.");

        UUID pendingStorageJob = UUID.randomUUID();
        afterRestart.createValidating(pending(pendingStorageJob));
        assertThat(new DocumentIngestionRepository(jdbc).closeInterruptedValidations()).isGreaterThanOrEqualTo(1);
        assertThat(afterRestart.find(pendingStorageJob).status()).isEqualTo("ORPHAN_REVIEW");
    }

    @Test
    @WithAnonymousUser
    void persistentImportStatusIsNotExposedByPublicDemoReads() throws Exception {
        mvc.perform(get("/api/v1/imports/documents/jobs/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void nonCuratorCannotSubmitOrReadImportJobs() throws Exception {
        mvc.perform(multipart("/api/v1/imports/documents").file(pdf()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/imports/documents/jobs/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    private DocumentImportJob awaitStatus(UUID jobId, String expected) throws Exception {
        InstantDeadline deadline = new InstantDeadline(Duration.ofSeconds(6));
        DocumentImportJob job;
        do {
            job = service.job(jobId);
            if (expected.equals(job.status())) return job;
            Thread.sleep(20);
        } while (!deadline.expired());
        throw new AssertionError("Job " + jobId + " remained " + job.status() + " instead of reaching " + expected + ".");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "research-continuity.pdf", "application/pdf", PDF);
    }

    private static DocumentIngestionRepository.PendingDocument pending(UUID jobId) {
        return new DocumentIngestionRepository.PendingDocument(jobId, UUID.randomUUID(), UUID.randomUUID(),
                "imports/test/" + jobId + "/recovery.pdf", "recovery.pdf", "application/pdf", 128,
                "a".repeat(64), "admin@ugnay.local", "test-extractor", 50_000, 4);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary TestStorage testStorage() { return new TestStorage(); }
        @Bean @Primary TestScanner testScanner() { return new TestScanner(); }
        @Bean @Primary TestExtractor testExtractor() { return new TestExtractor(); }
    }

    static final class TestStorage implements DocumentObjectStorage {
        final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger storeCount = new AtomicInteger();
        volatile Map<String, String> lastMetadata = Map.of();
        volatile boolean failStore;

        @Override
        public StoredObject store(String objectKey, Path source, String contentType, Map<String, String> metadata) throws IOException {
            storeCount.incrementAndGet();
            if (failStore) throw new IOException("Uncertain object-store outcome.");
            objects.put(objectKey, Files.readAllBytes(source));
            lastMetadata = Map.copyOf(metadata);
            return new StoredObject(objectKey, "test-etag");
        }

        @Override
        public InputStream open(String objectKey) throws IOException {
            byte[] value = objects.get(objectKey);
            if (value == null) throw new IOException("Missing test object.");
            return new ByteArrayInputStream(value);
        }

        void reset() {
            objects.clear();
            lastMetadata = Map.of();
            storeCount.set(0);
            failStore = false;
        }
    }

    static final class TestScanner implements MalwareScanner {
        final AtomicInteger scanCount = new AtomicInteger();
        volatile ScanResult result = ScanResult.CLEAN;

        @Override public ScanResult scan(Path source) {
            scanCount.incrementAndGet();
            return result;
        }

        void reset() {
            result = ScanResult.CLEAN;
            scanCount.set(0);
        }
    }

    static final class TestExtractor implements PdfTextExtractor {
        volatile CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile ExtractionOutcome outcome = ExtractionOutcome.extracted("Evidence ".repeat(80), 1);
        volatile boolean block;

        @Override
        public ExtractionOutcome extract(InputStream source, String filename, int maxCharacters, int timeoutSeconds) {
            started.countDown();
            if (block) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new ExtractionOutcome("INTERRUPTED", "", 0, "Interrupted test extraction.");
                }
            }
            return outcome;
        }

        void reset(ExtractionOutcome next, boolean shouldBlock) {
            outcome = next;
            block = shouldBlock;
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    private static final class InstantDeadline {
        private final long deadline;
        InstantDeadline(Duration duration) { deadline = System.nanoTime() + duration.toNanos(); }
        boolean expired() { return System.nanoTime() >= deadline; }
    }
}

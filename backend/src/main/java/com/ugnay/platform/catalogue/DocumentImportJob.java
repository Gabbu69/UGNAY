package com.ugnay.platform.catalogue;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DocumentImportJob(
        UUID jobId,
        UUID documentId,
        UUID documentVersionId,
        String originalFilename,
        String mimeType,
        long byteSize,
        String sha256,
        String scanStatus,
        String storageStatus,
        String objectKey,
        String storageEtag,
        String status,
        int progressPercent,
        int pageCount,
        int extractedCharacterCount,
        int maxCharacterCount,
        int timeoutSeconds,
        int attemptCount,
        boolean manualReviewRequired,
        boolean publicationEligible,
        String failureReason,
        String textPreview,
        String uploaderEmail,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt) {
    private static final Set<String> TERMINAL = Set.of(
            "EXTRACTED", "CHARACTER_LIMIT_REACHED", "TIMED_OUT", "INTERRUPTED", "FAILED",
            "FAILED_STORAGE", "ORPHAN_REVIEW");

    public boolean terminal() {
        return TERMINAL.contains(status);
    }
}

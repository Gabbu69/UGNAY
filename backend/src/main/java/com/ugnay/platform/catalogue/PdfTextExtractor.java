package com.ugnay.platform.catalogue;

import java.io.InputStream;

public interface PdfTextExtractor {
    ExtractionOutcome extract(InputStream source, String filename, int maxCharacters, int timeoutSeconds);

    record ExtractionOutcome(String status, String text, int pageCount, String failureReason) {
        public ExtractionOutcome {
            text = text == null ? "" : text;
            pageCount = Math.max(0, pageCount);
        }

        public static ExtractionOutcome extracted(String text, int pageCount) {
            return new ExtractionOutcome("EXTRACTED", text, pageCount, null);
        }
    }
}

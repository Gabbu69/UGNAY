package com.ugnay.platform.catalogue;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
final class TikaPdfTextExtractor implements PdfTextExtractor {
    private static final List<String> PAGE_KEYS = List.of("xmpTPg:NPages", "Page-Count", "meta:page-count", "pdf:page-count");

    @Override
    public ExtractionOutcome extract(InputStream source, String filename, int maxCharacters, int timeoutSeconds) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Metadata metadata = new Metadata();
            metadata.set("resourceName", filename);
            Future<String> extraction = executor.submit(() -> new Tika().parseToString(source, metadata, maxCharacters));
            try {
                String text = extraction.get(timeoutSeconds, TimeUnit.SECONDS);
                String status = text.length() >= maxCharacters ? "CHARACTER_LIMIT_REACHED" : "EXTRACTED";
                return new ExtractionOutcome(status, text, pageCount(metadata),
                        status.equals("CHARACTER_LIMIT_REACHED") ? "Configured extraction character limit was reached." : null);
            } catch (TimeoutException exception) {
                extraction.cancel(true);
                return new ExtractionOutcome("TIMED_OUT", "", pageCount(metadata),
                        "Text extraction exceeded the configured timeout of " + timeoutSeconds + " seconds.");
            } catch (InterruptedException exception) {
                extraction.cancel(true);
                Thread.currentThread().interrupt();
                return new ExtractionOutcome("INTERRUPTED", "", pageCount(metadata), "Text extraction was interrupted.");
            } catch (ExecutionException exception) {
                String reason = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                return new ExtractionOutcome("FAILED", "", pageCount(metadata), abbreviate(reason));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static int pageCount(Metadata metadata) {
        for (String key : PAGE_KEYS) {
            String value = metadata.get(key);
            if (value == null) continue;
            try { return Math.max(0, Integer.parseInt(value)); }
            catch (NumberFormatException ignored) { /* try the next parser-specific key */ }
        }
        return 0;
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) return "Tika could not extract text from the stored PDF.";
        return value.length() <= 900 ? value : value.substring(0, 897) + "...";
    }
}

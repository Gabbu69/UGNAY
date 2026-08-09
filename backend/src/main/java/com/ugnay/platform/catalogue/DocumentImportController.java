package com.ugnay.platform.catalogue;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports/documents")
@PreAuthorize("hasRole('CURATOR')")
public class DocumentImportController {
    private final DocumentIngestionService documents;

    public DocumentImportController(DocumentIngestionService documents) {
        this.documents = documents;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentIngestionService.DocumentAccepted> submit(
            @RequestPart("file") MultipartFile file, Principal principal) throws IOException {
        var accepted = documents.submit(file, principal.getName());
        return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(accepted);
    }

    @GetMapping("/jobs/{jobId}")
    public DocumentImportJob status(@PathVariable UUID jobId) {
        return documents.job(jobId);
    }

    @GetMapping(value = "/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId) throws IOException {
        return documents.events(jobId);
    }
}

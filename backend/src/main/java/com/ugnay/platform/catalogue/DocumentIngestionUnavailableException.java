package com.ugnay.platform.catalogue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public final class DocumentIngestionUnavailableException extends RuntimeException {
    public DocumentIngestionUnavailableException(String message) {
        super(message);
    }

    public DocumentIngestionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

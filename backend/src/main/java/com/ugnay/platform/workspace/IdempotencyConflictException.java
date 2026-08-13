package com.ugnay.platform.workspace;

/** Raised when a caller reuses an intake key for a different normalized request. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}

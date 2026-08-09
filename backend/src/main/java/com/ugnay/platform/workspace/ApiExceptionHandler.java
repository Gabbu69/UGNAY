package com.ugnay.platform.workspace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Request cannot be applied", exception.getMessage(), request);
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ProblemDetail precondition(PreconditionFailedException exception, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_FAILED, "Stale resource version", exception.getMessage(), request);
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException exception, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "Missing resource version", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflicting record",
                "The request conflicts with an existing account or scholarly record.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more request fields are invalid.", request);
        detail.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> java.util.Map.of("field", error.getField(), "message", error.getDefaultMessage())).toList());
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String message, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message == null ? title : message);
        detail.setTitle(title);
        detail.setType(URI.create("https://ugnay.local/problems/" + status.value()));
        detail.setInstance(URI.create(request.getRequestURI()));
        return detail;
    }
}

package com.example.common.web.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized exception handling for all microservices.
 * Returns RFC 9457 (formerly RFC 7807) Problem Details.
 * <p>
 * Extends {@link ResponseEntityExceptionHandler} so the standard Spring MVC exceptions it
 * already knows about (malformed JSON, unknown routes, unsupported HTTP methods, missing
 * request parameters, type mismatches, ...) get their correct 4xx status instead of falling
 * through to the generic 500 handler below. Only {@link #handleMethodArgumentNotValid} is
 * overridden, to keep this service's existing validation error response shape.
 * <p>
 * In production, error details are sanitized to prevent information leakage.
 * In development, additional debug information is included.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final boolean isProduction;

    public GlobalExceptionHandler(Environment environment) {
        this.isProduction = environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    /**
     * Preserves the existing validation error response shape (README-documented, asserted on
     * by tests): title "Input Validation Error", detail "Validation Failed", and an
     * "errors" property mapping field name to message.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation Failed");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setTitle("Input Validation Error");

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        problemDetail.setProperty("errors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /**
     * Same "errors" shape as {@link #handleMethodArgumentNotValid}, but for method/service-level
     * {@code @Validated} violations (property path → message) instead of {@code @Valid} DTO
     * binding failures.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation Failed");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setTitle("Input Validation Error");

        Map<String, String> fieldErrors = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        problemDetail.setProperty("errors", fieldErrors);

        return problemDetail;
    }

    /**
     * A unique-constraint / foreign-key / not-null violation surfaced by the persistence layer
     * (e.g. a duplicate profile). Mapped to 409 with a generic detail - never the underlying
     * SQL message, which can leak schema/column names.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request could not be completed because it conflicts with existing data");
        problemDetail.setTitle("Conflict");
        return problemDetail;
    }

    /**
     * Rethrows Spring Security's authorization/authentication exceptions instead of converting
     * them to a response here.
     * <p>
     * Without this, the generic {@link #handleGenericException} below would catch these and
     * return a 500, masking the real 403/401 (confirmed live: a non-admin POST to /admin/users
     * was returning 500 instead of 403). Rethrowing lets the exception propagate out of the
     * servlet so Spring Security's {@code ExceptionTranslationFilter} - which runs earlier in
     * the filter chain, outside of this controller advice - can catch it and produce the
     * correct 403 ({@code AuthorizationDeniedException} extends {@link AccessDeniedException})
     * or 401 ({@link AuthenticationException}) response.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(Exception ex) throws Exception {
        throw ex;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        problemDetail.setTitle(reasonPhrase(ex.getStatusCode()));
        return problemDetail;
    }

    /**
     * Resolves a plain reason phrase (e.g. "Conflict") for the title instead of
     * {@code HttpStatusCode#toString()} (e.g. "409 CONFLICT"), falling back to the raw code
     * for non-standard statuses {@link HttpStatus} can't resolve.
     */
    private static String reasonPhrase(HttpStatusCode statusCode) {
        try {
            return HttpStatus.valueOf(statusCode.value()).getReasonPhrase();
        } catch (IllegalArgumentException e) {
            return statusCode.toString();
        }
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred"
        );
        problemDetail.setTitle("Internal Server Error");

        // Include exception details only in non-production environments
        if (!isProduction) {
            problemDetail.setProperty("exception", ex.getClass().getSimpleName());
            problemDetail.setProperty("message", ex.getMessage());
        }

        return problemDetail;
    }
}

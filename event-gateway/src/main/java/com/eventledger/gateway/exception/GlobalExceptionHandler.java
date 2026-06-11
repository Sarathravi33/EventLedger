package com.eventledger.gateway.exception;

import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.model.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Centralised exception-to-HTTP-response mapping for the Event Gateway.
 *
 * @RestControllerAdvice intercepts exceptions thrown by any @RestController in
 * this application and converts them to ResponseEntity<ErrorResponse> before the
 * response is written. Controllers contain no try/catch blocks — all error mapping
 * lives here.
 *
 * Handler methods are ordered from most-specific to least-specific. Spring picks
 * the handler whose declared exception type most closely matches the thrown type,
 * so the generic Exception catch-all only fires when nothing else matches.
 *
 * Logging levels:
 *   DEBUG — validation failures and 404s (expected, high-volume, not actionable)
 *   WARN  — business rule violations (unexpected but not server faults)
 *   ERROR — anything unhandled (always worth investigation)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // 400 Bad Request
    // -------------------------------------------------------------------------

    /**
     * Handles @Valid failures on @RequestBody parameters.
     *
     * Spring MVC throws this when the deserialized EventRequest fails one or more
     * Bean Validation constraints. BindingResult contains every field violation so
     * all problems are reported together — the client does not need to fix and
     * resubmit repeatedly to discover each constraint.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        log.debug("Request validation failed — {} violation(s): {}", details.size(), details);
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse("Validation failed", details));
    }

    /**
     * Handles constraint violations from @Validated on method-level parameters.
     *
     * Covers path variables and query parameters annotated with constraints when
     * @Validated is present on the controller class. Strips the method-name prefix
     * from the property path (e.g. "getById.eventId" → "eventId") for cleaner output.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(cv -> {
                    String path = cv.getPropertyPath().toString();
                    // "methodName.fieldName" → "fieldName" for readability
                    String field = path.contains(".")
                            ? path.substring(path.lastIndexOf('.') + 1)
                            : path;
                    return field + ": " + cv.getMessage();
                })
                .toList();
        log.debug("Constraint violation — {} violation(s): {}", details.size(), details);
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse("Validation failed", details));
    }

    /**
     * Handles malformed or unreadable JSON request bodies.
     *
     * Jackson throws this before Bean Validation runs when the body cannot be
     * deserialized — e.g. a field holds the wrong type ("amount": "not-a-number"),
     * the JSON is syntactically broken, or the body is empty.
     *
     * Internal Jackson details are not forwarded to the client to avoid leaking
     * class names or stack information.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse("Malformed or unreadable JSON request body"));
    }

    /**
     * Handles missing required query parameters.
     *
     * Thrown when a @RequestParam without required=false or a defaultValue is
     * absent from the request. Example: GET /events without ?account=.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.debug("Missing required query parameter: {}", ex.getParameterName());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse("Missing required query parameter: " + ex.getParameterName()));
    }

    /**
     * Handles business rule violations thrown by the service layer.
     *
     * Service methods throw IllegalArgumentException for inputs that pass Bean
     * Validation but violate domain rules (e.g. referencing an account in a
     * currency that differs from existing transactions).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // 503 Service Unavailable
    // -------------------------------------------------------------------------

    /**
     * Maps Account Service failures to 503 Service Unavailable.
     *
     * Thrown by AccountServiceClient in two situations:
     *   - The HTTP call failed (network error, read timeout, non-2xx response) —
     *     the fallback method converts the raw exception to this type.
     *   - The Resilience4j circuit breaker is OPEN — the call was not attempted
     *     and CallNotPermittedException was caught by the fallback.
     *
     * The client receives a stable error message that does not leak internal
     * details (host names, port numbers, downstream error bodies).
     * The full cause is logged at WARN for operational visibility.
     */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAccountServiceUnavailable(
            AccountServiceUnavailableException ex) {
        log.warn("Account Service unavailable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Account Service unavailable — please try again later"));
    }

    // -------------------------------------------------------------------------
    // 404 Not Found
    // -------------------------------------------------------------------------

    /**
     * Handles resource not-found conditions.
     *
     * Service methods call Optional.orElseThrow(() -> new NoSuchElementException("..."))
     * when a requested entity does not exist in the Gateway's database.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        log.debug("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // 500 Internal Server Error
    // -------------------------------------------------------------------------

    /**
     * Catch-all for any exception not handled by the more specific handlers above.
     *
     * Logs the full stack trace at ERROR level for investigation. Returns a generic
     * message to the client — internal details (class names, stack frames, SQL errors)
     * must never be exposed in API responses.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception — returning 500", ex);
        return ResponseEntity
                .internalServerError()
                .body(new ErrorResponse("An unexpected error occurred"));
    }
}

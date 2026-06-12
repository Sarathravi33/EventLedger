package com.eventledger.account.exception;

import com.eventledger.account.model.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

/**
 * Centralised exception handler for the Account Service.
 *
 * Intercepts exceptions thrown from any controller, translates them to a
 * consistent {@link ErrorResponse} JSON body, and sets the correct HTTP status.
 * No controller in this service contains try/catch blocks or builds error
 * responses — all of that happens here.
 *
 * --- Handlers ---
 *   NoSuchElementException  → 404  thrown by AccountService when accountId is not found
 *   IllegalArgumentException → 400  thrown by AccountService for unrecognised transaction type
 *   Exception (catch-all)    → 500  any other unexpected failure
 *
 * The Account Service is internal (called only by the Gateway), so we do not
 * need the extensive validation-error handling that the Gateway's handler has.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles requests to paths that have no mapped controller or static resource.
     * NoResourceFoundException is thrown by Spring Framework 6.1's resource handler;
     * NoHandlerFoundException covers paths with no handler at all.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(Exception ex) {
        log.debug("Route not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("No endpoint found for the requested path"));
    }

    /**
     * 404 Not Found — account does not exist in the Account Service database.
     *
     * Thrown by AccountService.getBalance and AccountService.getAccount when
     * accountId has no matching row in the accounts table.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        log.debug("Account not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * 400 Bad Request — invalid input passed to a service method.
     *
     * Thrown by AccountService.applyTransaction if the transaction type is
     * neither "CREDIT" nor "DEBIT". In practice this should never reach the
     * Account Service because the Gateway validates the type upstream, but
     * the handler exists as a defensive layer.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * 500 Internal Server Error — any exception not matched above.
     *
     * Logged at ERROR level so that unexpected failures are visible in the
     * server log without any special monitoring setup.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error in Account Service", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("An unexpected error occurred"));
    }
}

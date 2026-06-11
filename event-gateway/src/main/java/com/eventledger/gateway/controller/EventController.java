package com.eventledger.gateway.controller;

import com.eventledger.gateway.model.dto.EventRequest;
import com.eventledger.gateway.model.dto.EventResponse;
import com.eventledger.gateway.model.dto.EventSubmitResult;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the Event Gateway's event endpoints.
 *
 * Responsibilities of this class:
 *   - Bind HTTP request → method parameter (Spring MVC)
 *   - Trigger Bean Validation via @Valid on the request body
 *   - Delegate all business logic to EventService
 *   - Map the service result → ResponseEntity with the correct HTTP status
 *
 * This controller contains no business logic, no try/catch blocks, and no
 * direct repository access. All error conditions are handled by GlobalExceptionHandler.
 *
 * --- Response codes ---
 *   POST /events       201 Created   — new event accepted and processed
 *                      200 OK        — duplicate eventId (added in Step 8)
 *                      400 Bad Request — validation failure
 *                      503 Service Unavailable — Account Service unreachable (Step 10)
 *   GET  /events/{id}  200 OK        — event found
 *                      404 Not Found — eventId does not exist
 *   GET  /events       200 OK        — list (may be empty, never 404)
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Accepts a new transaction event from an external client.
     *
     * @Valid triggers Bean Validation on EventRequest before this method body runs.
     * If any constraint fails, Spring MVC throws MethodArgumentNotValidException,
     * which GlobalExceptionHandler maps to 400 before this method is entered.
     *
     * Returns 201 Created for a new event (first submission of this eventId).
     * Returns 200 OK for a duplicate eventId — the existing event is returned
     * unchanged; no Account Service call is made on the duplicate path.
     *
     * The response body shape is identical for both status codes — clients that
     * do not need to distinguish new from duplicate can ignore the status code.
     */
    @PostMapping
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        EventSubmitResult result = eventService.submitEvent(request);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.event());
    }

    /**
     * Retrieves a single event from the Gateway's local database by its ID.
     *
     * This endpoint reads only from the Gateway's own H2 database and does not
     * call the Account Service. It remains available even when the Account
     * Service is down (graceful degradation, Step 10).
     *
     * Returns 404 when the eventId does not exist (NoSuchElementException →
     * GlobalExceptionHandler → 404).
     */
    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String eventId) {
        EventResponse response = eventService.getEventById(eventId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all events for the given account, sorted by eventTimestamp ascending.
     *
     * The ?account= query parameter is required. If absent, Spring MVC throws
     * MissingServletRequestParameterException → GlobalExceptionHandler → 400.
     *
     * Returns an empty array [] when the account has no events — never 404.
     *
     * Ordering by eventTimestamp (not arrival time) means a late-arriving event
     * with an older timestamp appears in its correct chronological position in
     * the list — out-of-order delivery is transparent to the client.
     *
     * This endpoint reads only from the Gateway's local database and remains
     * available even when the Account Service is down (Step 10).
     */
    @GetMapping
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        List<EventResponse> events = eventService.getEventsByAccount(accountId);
        return ResponseEntity.ok(events);
    }
}

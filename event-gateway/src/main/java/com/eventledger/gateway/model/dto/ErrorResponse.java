package com.eventledger.gateway.model.dto;

import java.util.List;

/**
 * Standard error shape returned by every error condition from the Gateway API.
 *
 * error:   A human-readable summary of what went wrong. Always present.
 *
 * details: A list of field-level violation messages. Only present for validation
 *          errors (400). Each entry is formatted as "fieldName: constraint message".
 *          Absent for non-validation errors because the application.yml setting
 *          spring.jackson.default-property-inclusion=non_null omits null fields.
 *
 * Examples:
 *
 *   Validation error (400):
 *   {
 *     "error": "Validation failed",
 *     "details": [
 *       "eventId: eventId is required",
 *       "amount: amount must be greater than 0"
 *     ]
 *   }
 *
 *   Not found (404):
 *   { "error": "Event not found: evt-999" }
 *
 *   Unexpected server error (500):
 *   { "error": "An unexpected error occurred" }
 */
public record ErrorResponse(
        String error,
        List<String> details
) {
    // Convenience constructor for errors that have no field-level details.
    // details is null and therefore absent from JSON output.
    public ErrorResponse(String error) {
        this(error, null);
    }
}

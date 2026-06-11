package com.eventledger.account.model.dto;

import java.util.List;

/**
 * Standard error envelope returned by the Account Service on all non-2xx responses.
 *
 * Matches the Gateway's ErrorResponse shape so callers can parse errors uniformly.
 *
 * --- details field ---
 * null for all errors in this service (no Bean Validation here — the Gateway
 * validates input before forwarding). Because application.yml sets
 * spring.jackson.default-property-inclusion: non_null, null fields are omitted
 * from the serialised JSON entirely, so clients receive just {"error": "..."}.
 */
public record ErrorResponse(String error, List<String> details) {

    // Convenience constructor for the common case where there are no details.
    // details is passed as null, which Jackson omits from the JSON output.
    public ErrorResponse(String error) {
        this(error, null);
    }
}

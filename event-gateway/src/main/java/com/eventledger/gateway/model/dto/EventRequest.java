package com.eventledger.gateway.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Inbound DTO for POST /events.
 *
 * Declared as a Java record — immutable by construction. Once deserialized,
 * the payload cannot be altered as it passes through the service layers.
 *
 * --- Validation ---
 * Bean Validation constraints are declared on the record components.
 * Spring MVC triggers validation when the controller parameter is annotated
 * with @Valid. All violations are collected and returned together by
 * GlobalExceptionHandler as a 400 response — the client gets the full list
 * of problems in one shot rather than fixing them one at a time.
 *
 * --- Instant deserialization ---
 * eventTimestamp arrives as an ISO-8601 string (e.g. "2026-05-15T14:02:11Z").
 * Jackson's JavaTimeModule, auto-configured by Spring Boot, converts it to
 * Instant. If the format is wrong, Jackson throws HttpMessageNotReadableException
 * before validation runs — also handled by GlobalExceptionHandler.
 *
 * --- metadata ---
 * Optional. Null when the client omits it. No validation constraint needed.
 */
public record EventRequest(

        // Must be present and non-blank. Acts as the idempotency key — the same
        // eventId submitted twice will not create a duplicate event or balance change.
        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "accountId is required")
        String accountId,

        // Only CREDIT and DEBIT are accepted. The @Pattern constraint is case-sensitive
        // and anchored (^ … $) so partial matches like "CREDIT_EXTRA" are also rejected.
        @NotBlank(message = "type is required")
        @Pattern(regexp = "^(CREDIT|DEBIT)$", message = "type must be CREDIT or DEBIT")
        String type,

        // @NotNull catches a missing field. @Positive rejects zero and negative values.
        // Both are needed: @Positive alone passes null through without failing.
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than 0")
        BigDecimal amount,

        // ISO 4217 currency codes are always exactly 3 characters (e.g. "USD", "EUR").
        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-character ISO 4217 code")
        String currency,

        // If the client sends a non-ISO-8601 string, Jackson fails before this
        // constraint is evaluated. @NotNull covers the case where the field is absent.
        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        Map<String, Object> metadata    // optional; null when not provided by client
) {}


package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Event Gateway REST endpoints.
 *
 * AccountServiceClient is replaced with a @MockBean so no real HTTP call
 * leaves the process. Mockito's default behaviour for a void method is to
 * do nothing, which means every applyTransaction call succeeds silently and
 * the event status advances to PROCESSED.
 *
 * Circuit breaker and tracing behaviour are tested separately in
 * CircuitBreakerTest and TracePropagationTest respectively.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class EventApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean AccountServiceClient accountServiceClient;

    @Test
    void postEvent_validPayload_returns201WithProcessedStatus() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEventJson("evt-api-submit-001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-api-submit-001"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.accountId").value("acc-api-1"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void postEvent_missingEventId_returns400WithErrorField() throws Exception {
        String body = """
                {
                    "accountId": "acc-1",
                    "type": "CREDIT",
                    "amount": 50.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void postEvent_invalidType_returns400() throws Exception {
        String body = """
                {
                    "eventId": "evt-api-bad-type",
                    "accountId": "acc-1",
                    "type": "TRANSFER",
                    "amount": 50.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postEvent_negativeAmount_returns400() throws Exception {
        String body = """
                {
                    "eventId": "evt-api-neg-amt",
                    "accountId": "acc-1",
                    "type": "CREDIT",
                    "amount": -10.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postEvent_currencyNot3Chars_returns400() throws Exception {
        String body = """
                {
                    "eventId": "evt-api-bad-curr",
                    "accountId": "acc-1",
                    "type": "CREDIT",
                    "amount": 50.00,
                    "currency": "US",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getEventById_existingEvent_returns200() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEventJson("evt-api-get-001")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events/evt-api-get-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-api-get-001"));
    }

    @Test
    void getEventById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/events/evt-does-not-exist-xyz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getEventsByAccount_missingAccountParam_returns400() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private String validEventJson(String eventId) {
        return """
                {
                    "eventId": "%s",
                    "accountId": "acc-api-1",
                    "type": "CREDIT",
                    "amount": 100.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """.formatted(eventId);
    }
}

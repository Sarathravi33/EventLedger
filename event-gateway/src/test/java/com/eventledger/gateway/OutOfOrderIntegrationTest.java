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
 * Integration test for out-of-order event delivery.
 *
 * Events are submitted in reverse chronological order (latest eventTimestamp first).
 * GET /events?account= must return them sorted by eventTimestamp ascending,
 * not by the order they were received (receivedAt / insertion order).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OutOfOrderIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean AccountServiceClient accountServiceClient;

    @Test
    void eventsSubmittedOutOfOrder_getReturnsThemSortedByEventTimestamp() throws Exception {
        // Submit C (latest timestamp) first — as if it arrived before older events
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-ooo-c", "2026-06-03T10:00:00Z")))
                .andExpect(status().isCreated());

        // Submit A (earliest timestamp) second
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-ooo-a", "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());

        // Submit B (middle timestamp) last
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-ooo-b", "2026-06-02T10:00:00Z")))
                .andExpect(status().isCreated());

        // GET must return A → B → C (eventTimestamp ASC), not C → A → B (arrival order)
        mockMvc.perform(get("/events").param("account", "acc-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventId").value("evt-ooo-a"))
                .andExpect(jsonPath("$[1].eventId").value("evt-ooo-b"))
                .andExpect(jsonPath("$[2].eventId").value("evt-ooo-c"));
    }

    // --- helpers ---

    private String event(String eventId, String timestamp) {
        return """
                {
                    "eventId": "%s",
                    "accountId": "acc-ooo",
                    "type": "CREDIT",
                    "amount": 10.00,
                    "currency": "USD",
                    "eventTimestamp": "%s"
                }
                """.formatted(eventId, timestamp);
    }
}

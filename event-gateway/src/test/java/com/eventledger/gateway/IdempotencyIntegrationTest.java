package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Gateway idempotency: submitting the same eventId twice
 * must return 201 the first time and 200 the second time, without calling the
 * Account Service again on the duplicate request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean AccountServiceClient accountServiceClient;

    @Test
    void submitSameEventTwice_firstIs201_secondIs200() throws Exception {
        String body = validEventJson("evt-idem-001");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-idem-001"));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-idem-001"));
    }

    @Test
    void submitSameEventTwice_accountServiceCalledOnlyOnce() throws Exception {
        String body = validEventJson("evt-idem-002");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // Second request hits the idempotency check before any write or Account Service call.
        // @MockBean is reset between test methods — verify applies only within this test.
        verify(accountServiceClient, times(1))
                .applyTransaction(any(), eq("evt-idem-002"), any(), any(), any(), any());
    }

    @Test
    void duplicateReturnsIdenticalResponseBody() throws Exception {
        String body = validEventJson("evt-idem-003");

        MvcResult first = mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    // --- helpers ---

    private String validEventJson(String eventId) {
        return """
                {
                    "eventId": "%s",
                    "accountId": "acc-idem-1",
                    "type": "DEBIT",
                    "amount": 25.00,
                    "currency": "GBP",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """.formatted(eventId);
    }
}

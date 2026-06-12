package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the Resilience4j circuit breaker around AccountServiceClient.
 *
 * A WireMockExtension stubs the Account Service so it always returns HTTP 500.
 * The circuit breaker configuration is overridden to a 2-call window so the
 * circuit opens quickly in the test environment.
 *
 * Key assertion: once the circuit opens, subsequent Gateway calls must NOT
 * produce additional HTTP requests to the Account Service (verified via WireMock
 * request counts). The caller still receives 503 — just without a network round-trip.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // Override window size so the circuit opens after 2 failures, not 10.
                "resilience4j.circuitbreaker.instances.accountService.sliding-window-size=2",
                "resilience4j.circuitbreaker.instances.accountService.minimum-number-of-calls=2",
                "resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50",
                // Long wait so the circuit stays OPEN for the duration of this test.
                "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=60s"
        }
)
@AutoConfigureMockMvc
class CircuitBreakerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureAccountServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", wm::baseUrl);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void circuitOpensAfterConsecutiveFailures_subsequentCallsNotForwardedToAccountService()
            throws Exception {
        // Account Service is down — every request returns 500.
        wm.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        // Two calls fill the sliding window (size=2, threshold=50%).
        // Both reach WireMock: 500 response → fallback → AccountServiceUnavailableException → 503.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validEventJson("evt-cb-" + i)))
                    .andExpect(status().isServiceUnavailable());
        }

        // Circuit is now OPEN. A third call must short-circuit immediately — no HTTP
        // request reaches WireMock. The caller still receives 503.
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEventJson("evt-cb-open")))
                .andExpect(status().isServiceUnavailable());

        // Exactly 2 requests reached WireMock — the 3rd was blocked by the open circuit.
        wm.verify(exactly(2), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    // --- helpers ---

    private String validEventJson(String eventId) {
        return """
                {
                    "eventId": "%s",
                    "accountId": "acc-cb",
                    "type": "CREDIT",
                    "amount": 10.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """.formatted(eventId);
    }
}

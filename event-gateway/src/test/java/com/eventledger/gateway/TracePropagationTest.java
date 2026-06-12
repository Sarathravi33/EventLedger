package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Integration test for W3C Trace Context propagation.
 *
 * When the Gateway forwards an event to the Account Service, the outgoing
 * RestTemplate call must carry a W3C traceparent header so that the Account
 * Service's spans can be correlated with the Gateway's spans by traceId.
 *
 * Uses RANDOM_PORT (real Tomcat) rather than MOCK (MockMvc) because OTel's
 * Context.current() is only populated by the server span when the full Servlet
 * filter chain runs. In the mock environment the scope is not carried through
 * to the same thread context that RestTemplate uses.
 *
 * A WireMockExtension stubs the Account Service endpoint and captures the
 * incoming request. After the Gateway call completes, WireMock verifies that
 * the traceparent header was present and well-formed.
 *
 * Format: 00-{32-hex traceId}-{16-hex spanId}-{flags}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracePropagationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureAccountServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", wm::baseUrl);
    }

    @Autowired private TestRestTemplate testRestTemplate;

    @Test
    void postEvent_traceparentHeaderForwardedToAccountService() {
        wm.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("""
                {
                    "eventId": "evt-trace-001",
                    "accountId": "acc-trace",
                    "type": "CREDIT",
                    "amount": 50.00,
                    "currency": "USD",
                    "eventTimestamp": "2026-06-01T10:00:00Z"
                }
                """, headers);

        testRestTemplate.exchange("/events", HttpMethod.POST, entity, String.class);

        // RestTemplateConfig adds a ClientHttpRequestInterceptor that calls the OTel
        // TextMapPropagator.inject(), which reads Context.current() — populated by
        // ServerHttpObservationFilter for this real HTTP request — and sets traceparent.
        // Format: 00-{32-hex traceId}-{16-hex spanId}-{2-hex flags}
        wm.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("traceparent", matching("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")));
    }
}

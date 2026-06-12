package com.eventledger.gateway.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountServiceClient HTTP mechanics.
 *
 * These tests instantiate AccountServiceClient directly (without Spring),
 * so the @CircuitBreaker AOP proxy is NOT active. The fallback
 * (applyTransactionFallback) is only called when the circuit breaker proxy
 * is present; in unit tests, exceptions propagate directly from the method body.
 * Circuit breaker behaviour is tested end-to-end in CircuitBreakerTest.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceClientTest {

    @Mock private RestTemplate restTemplate;

    private AccountServiceClient client;
    private static final String BASE_URL = "http://account-service";

    @BeforeEach
    void setUp() {
        client = new AccountServiceClient(restTemplate, BASE_URL);
    }

    @Test
    void applyTransaction_buildsCorrectUrlFromAccountId() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.status(201).build());

        client.applyTransaction("acc-99", "txn-1", "CREDIT",
                BigDecimal.valueOf(100), "USD", Instant.now());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), any(), eq(Void.class));
        assertThat(urlCaptor.getValue())
                .isEqualTo(BASE_URL + "/accounts/acc-99/transactions");
    }

    @Test
    void applyTransaction_withoutAopProxy_resourceAccessExceptionPropagatesDirectly() {
        // Without the Spring AOP proxy, ResourceAccessException is not intercepted
        // by the fallback — it propagates straight out of the method body.
        // The fallback wrapping happens in the integration context (CircuitBreakerTest).
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() ->
                client.applyTransaction("acc-1", "txn-1", "CREDIT",
                        BigDecimal.TEN, "USD", Instant.now()))
                .isInstanceOf(ResourceAccessException.class);
    }
}

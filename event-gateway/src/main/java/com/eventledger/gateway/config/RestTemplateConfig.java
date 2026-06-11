package com.eventledger.gateway.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Produces the {@link RestTemplate} bean used by {@link com.eventledger.gateway.client.AccountServiceClient}.
 *
 * Using {@link RestTemplateBuilder} (Spring Boot auto-configures it) ensures the
 * application's Jackson settings apply on the wire — see Step 7 for the full rationale.
 *
 * --- Timeouts ---
 * connectTimeout: 2 s — fail fast if the Account Service TCP socket cannot be opened.
 * readTimeout:    3 s — abort if the Account Service accepts the connection but does
 *                       not produce a complete response within 3 seconds.
 *
 * Both timeouts throw {@link org.springframework.web.client.ResourceAccessException}
 * when they fire. The {@code @CircuitBreaker} around {@code applyTransaction} counts
 * these as failures and, once the failure rate threshold is exceeded, opens the circuit
 * so subsequent calls short-circuit immediately without waiting for the timeout.
 *
 * The combination of HTTP-level timeouts (here) and the circuit breaker (in
 * AccountServiceClient) means the worst-case latency added to a POST /events call
 * when the Account Service is down is one 3-second timeout; after that the circuit
 * opens and all further calls fail instantly.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .build();
    }
}

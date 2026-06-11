package com.eventledger.gateway.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Produces the {@link RestTemplate} bean used by {@link com.eventledger.gateway.client.AccountServiceClient}.
 *
 * Spring Boot does not auto-configure a RestTemplate bean — it must be declared
 * explicitly. Using {@link RestTemplateBuilder} (which Spring Boot does auto-configure)
 * is important because the builder applies the application's HttpMessageConverters,
 * including the Jackson converter configured by application.yml:
 *
 *   spring.jackson.serialization.write-dates-as-timestamps: false
 *   spring.jackson.default-property-inclusion: non_null
 *
 * A plain {@code new RestTemplate()} would create its own default ObjectMapper and
 * ignore these settings, causing Instant fields to be serialised differently than
 * the Account Service expects.
 *
 * Timeouts (connect timeout, read timeout) are intentionally not set here.
 * They will be added in Step 10 when Resilience4j TimeLimiter handles the
 * timeout concern at the circuit-breaker level.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}

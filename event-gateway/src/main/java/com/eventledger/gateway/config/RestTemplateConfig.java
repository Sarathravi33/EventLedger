package com.eventledger.gateway.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 *
 * --- W3C Trace Context propagation ---
 * Each outgoing RestTemplate call gets a child span. The W3C traceparent header is
 * constructed directly from Micrometer's TraceContext fields (traceId, spanId, sampled)
 * rather than delegating to the OTel propagator chain, which is unreliable in some
 * Spring Boot test environments where the OTel TextMapPropagator list may be empty.
 *
 * The traceparent format is: 00-{32-hex traceId}-{16-hex spanId}-{flags}
 * where flags=01 if sampled, 00 if not sampled.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, Tracer tracer) {
        return builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(Duration.ofSeconds(2));
                    factory.setReadTimeout(Duration.ofSeconds(3));
                    return factory;
                })
                .additionalInterceptors((request, body, execution) -> {
                    Span span = tracer.nextSpan().name("account-service.call").start();
                    try {
                        TraceContext ctx = span.context();
                        String traceId = ctx.traceId();
                        String spanId = ctx.spanId();
                        if (traceId != null && spanId != null) {
                            String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
                            request.getHeaders().set("traceparent",
                                    "00-" + traceId + "-" + spanId + "-" + flags);
                        }
                        return execution.execute(request, body);
                    } finally {
                        span.end();
                    }
                })
                .build();
    }
}

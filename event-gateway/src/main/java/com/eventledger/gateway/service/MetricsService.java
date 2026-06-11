package com.eventledger.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Records Micrometer metrics for the Event Gateway's two observable operations:
 * event submission outcomes and Account Service call durations.
 *
 * All metrics are registered lazily on first use via the builder pattern — no
 * pre-registration in a constructor or @PostConstruct needed. Micrometer
 * de-duplicates builders with the same name+tags, so calling
 * {@code Counter.builder("x").tag("k","v").register(registry)} twice returns
 * the same counter instance.
 *
 * --- Metrics produced ---
 *
 * Counter: events.submitted
 *   Tags: type   (CREDIT | DEBIT)
 *         status (PROCESSED | FAILED | DUPLICATE)
 *   Queryable at: GET /actuator/metrics/events.submitted
 *   Use: total submission volume split by outcome; detect spike in FAILEDs
 *        (Account Service degradation) or DUPLICATEs (client retry storm).
 *
 * Timer: account.service.call.duration
 *   Tags: outcome (success | failure)
 *   Queryable at: GET /actuator/metrics/account.service.call.duration
 *   Use: p99 latency of calls to the Account Service; the timer stops when
 *        the call returns or the fallback fires, so it measures wall-clock
 *        time including connect/read timeouts and circuit breaker overhead.
 */
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the {@code events.submitted} counter for the given event type and outcome.
     *
     * @param type   "CREDIT" or "DEBIT" — taken from the event payload
     * @param status "PROCESSED", "FAILED", or "DUPLICATE" — set by EventService
     */
    public void recordEventSubmitted(String type, String status) {
        Counter.builder("events.submitted")
                .description("Total events submitted to the Event Gateway")
                .tag("type", type)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Starts a timer sample for measuring an Account Service call.
     *
     * Call {@link #recordAccountServiceCall(Timer.Sample, String)} when the call
     * completes (success or failure) to stop the timer and record the duration.
     *
     * @return a {@link Timer.Sample} capturing the start instant
     */
    public Timer.Sample startAccountServiceCallTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops the timer sample and records the duration under {@code account.service.call.duration}.
     *
     * @param sample  the sample returned by {@link #startAccountServiceCallTimer()}
     * @param outcome "success" if the call returned 2xx; "failure" for any exception
     */
    public void recordAccountServiceCall(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("account.service.call.duration")
                .description("Duration of Account Service HTTP calls from the Gateway")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }
}

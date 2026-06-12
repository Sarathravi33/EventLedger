# Step 12 — Fixes Applied During Test Run

## Fix 1 — `RestTemplateConfig`: Replace `Propagator.inject()` with direct traceparent construction

**File:** `event-gateway/src/main/java/com/eventledger/gateway/config/RestTemplateConfig.java`

**Test that caught it:** `TracePropagationTest.postEvent_traceparentHeaderForwardedToAccountService`

**Symptom:** WireMock received the POST to `/accounts/.*/transactions` but the `traceparent` header was absent. The test assertion:
```
wm.verify(postRequestedFor(...).withHeader("traceparent", matching("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")))
```
failed with "No requests exactly matched."

**Root cause:** The interceptor called `Propagator.inject()` (Micrometer's OTel bridge) to write the traceparent header. In Spring Boot's test context the OTel `TextMapPropagator` list is empty — the `ContextPropagators` bean is created from `List<TextMapPropagator>` beans, and none are registered in the test environment. The composite propagator is therefore a no-op and the setter lambda is never invoked. The span context itself was valid (traceId and spanId both non-zero, sampled=true) — only the propagation mechanism was broken.

**Fix:** Replaced the `Propagator.inject()` call with direct W3C traceparent header construction from Micrometer's `TraceContext` fields:

```java
// Before (broken — propagator is a no-op in test context)
propagator.inject(span.context(), request.getHeaders(),
        (headers, key, value) -> headers.set(key, value));

// After (direct construction — no dependency on OTel propagator chain)
TraceContext ctx = span.context();
String traceId = ctx.traceId();
String spanId  = ctx.spanId();
if (traceId != null && spanId != null) {
    String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
    request.getHeaders().set("traceparent",
            "00-" + traceId + "-" + spanId + "-" + flags);
}
```

The `Propagator` parameter was also removed from the `restTemplate` bean method signature since it is no longer used.

**Why this is correct:** The W3C traceparent format is `00-{32-hex traceId}-{16-hex spanId}-{2-hex flags}`. Micrometer's `TraceContext.traceId()` and `TraceContext.spanId()` return lowercase hex strings of the required lengths. Constructing the header directly is equivalent to what `W3CTraceContextPropagator.inject()` would have done, without depending on the propagator chain being configured.

---

## Test results after fix

```
Event Gateway   — 24 tests, 0 failures
Account Service — 15 tests, 0 failures
Total           — 39 tests, BUILD SUCCESS
```

| Test class | Tests | What it covers |
|---|---|---|
| `EventServiceTest` | 8 | Idempotency, status transitions, metadata round-trip, ordering delegation |
| `AccountServiceClientTest` | 2 | URL construction, raw exception propagation without AOP proxy |
| `EventApiIntegrationTest` | 8 | POST/GET flows, validation rejections (400), not-found (404) |
| `IdempotencyIntegrationTest` | 3 | 201 first / 200 duplicate, Account Service called once, identical response body |
| `OutOfOrderIntegrationTest` | 1 | Events submitted in reverse order returned sorted by `eventTimestamp ASC` |
| `CircuitBreakerTest` | 1 | Circuit opens after 2 failures; 3rd call short-circuits without hitting WireMock |
| `TracePropagationTest` | 1 | W3C `traceparent` header present on every outgoing Account Service call |
| `AccountServiceTest` | 7 | Credit/debit balance math, auto-creation, idempotency, commutativity, not-found |
| `AccountApiIntegrationTest` | 8 | Full REST flows, balance, history, out-of-order history ordering |

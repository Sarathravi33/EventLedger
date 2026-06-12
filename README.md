# Event Ledger

A distributed financial transaction event system composed of two independently runnable microservices. The system receives transaction events from upstream sources, enforces idempotency, handles out-of-order delivery, and maintains accurate account balances.

---

## Architecture Overview

```
                          ┌──────────────────────┐
Browser / Client ──────→  │  Event Gateway API    │
                          │  (public-facing)      │
                          │  Port: 8080           │
                          └──────┬───────────────┘
                                 │ REST (sync)
                                 │ W3C traceparent header
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service      │
                          │  (internal)           │
                          │  Port: 8081           │
                          └──────────────────────┘
```

> **Port conflict note:** If port 8080 is already in use on your machine, start the Gateway with `--server.port=9090` (or any free port). The Account Service URL the Gateway uses is controlled by the `account-service.base-url` property and defaults to `http://localhost:8081` — it is unaffected by the Gateway's own port.

### Event Gateway API (`event-gateway`, port 8080)

The public entry point for all client requests. Responsibilities:

- Receives and validates incoming transaction events
- Enforces idempotency using `eventId` as a primary key — duplicate submissions are detected and short-circuited before any downstream call is made
- Persists event records in its own embedded H2 database
- Propagates each request to the Account Service to apply the transaction
- Applies a **circuit breaker** on all calls to the Account Service — if the Account Service is repeatedly unavailable, the circuit opens and clients receive a `503` immediately rather than waiting for a timeout
- Serves `GET /events` queries entirely from its own local data, so read operations remain available even when the Account Service is down

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account, ordered by event timestamp |
| `GET` | `/health` | Custom health check (probes H2 with `SELECT 1`) |
| `GET` | `/actuator/health` | Spring Boot Actuator health — includes circuit breaker state |
| `GET` | `/actuator/metrics` | Micrometer metrics (`events.submitted`, `account.service.call.duration`) |

### Account Service (`account-service`, port 8081)

An internal service, not exposed to external clients. Responsibilities:

- Manages account state: balance, transaction history
- Auto-creates an account on the first transaction if it does not yet exist
- Computes balance as `SUM(CREDIT amounts) − SUM(DEBIT amounts)` — this is order-independent by design, so out-of-order event arrival never produces an incorrect balance
- Enforces its own idempotency check on `transactionId` as a second line of defence
- Returns transactions ordered by `eventTimestamp` (the original event time), not by insertion time

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction to an account |
| `GET` | `/accounts/{accountId}/balance` | Get the current balance |
| `GET` | `/accounts/{accountId}` | Get account details and transaction history |
| `GET` | `/health` | Custom health check (probes H2 with `SELECT 1`) |
| `GET` | `/actuator/health` | Spring Boot Actuator health |
| `GET` | `/actuator/metrics` | Standard JVM and HTTP metrics |

### How the Services Interact

1. Client submits `POST /events` to the Gateway.
2. Gateway validates the payload, checks for a duplicate `eventId`, and saves the event locally with status `PENDING`.
3. Gateway calls `POST /accounts/{accountId}/transactions` on the Account Service (wrapped in a circuit breaker), injecting a W3C `traceparent` header so the two services share the same trace ID in their logs.
4. Account Service applies the transaction and returns the updated balance.
5. Gateway updates the event status to `PROCESSED` and returns `201 Created` to the client.

If the Account Service is unreachable, the circuit breaker opens and the Gateway returns `503 Service Unavailable`. Read endpoints (`GET /events/{id}`, `GET /events?account=`) are unaffected and continue to serve from the Gateway's local database.

### Data Isolation

Each service owns its own embedded H2 in-memory database. They share no database, no schema, and no in-process state. The only communication channel is the HTTP API defined above.

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java (JDK) | 21 or later | `java -version` to check |
| Maven | 3.9 or later | `mvn -version` to check |
| Docker + Docker Compose | 24+ / 2.x+ | Only required for the Docker Compose startup path |

To verify your Java and Maven installations:

```bash
java -version
mvn -version
```

---

## Installing Dependencies

Dependencies are declared in each module's `pom.xml` and fetched automatically by Maven from Maven Central on first build. No manual installation is required beyond Java and Maven themselves.

To pre-fetch all dependencies without building:

```bash
mvn dependency:resolve
```

---

## Starting Both Services

### Option A — Docker Compose (recommended)

Builds both services and starts them in containers with a single command.

```bash
docker-compose up --build
```

- Event Gateway will be available at `http://localhost:8080`
- Account Service will be available at `http://localhost:8081`

To stop and remove containers:

```bash
docker-compose down
```

### Option B — Manual (two terminal windows)

**Terminal 1 — Account Service** (start this first):

```bash
cd account-service
mvn spring-boot:run
```

Wait until you see:

```
Started AccountServiceApplication in X.XXX seconds
```

**Terminal 2 — Event Gateway:**

```bash
cd event-gateway
mvn spring-boot:run
```

Wait until you see:

```
Started EventGatewayApplication in X.XXX seconds
```

Both services are now running. The Gateway is configured to reach the Account Service at `http://localhost:8081`.

> **If port 8080 is already in use**, start the Gateway on a different port:
> ```bash
> cd event-gateway
> mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9090
> ```
> Or when running the packaged JAR:
> ```bash
> java -jar event-gateway/target/event-gateway-1.0.0.jar --server.port=9090
> ```
> All Postman collection requests use the `{{gateway_url}}` variable — update that variable to match the port you chose.

### Verifying Startup

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Both should return `200 OK` with:

```json
{
  "status": "UP",
  "service": "event-gateway",
  "database": "UP",
  "timestamp": "2026-06-12T09:00:00Z"
}
```

(`"service"` will be `"account-service"` for the Account Service response.)

You can also check the Spring Boot Actuator health endpoint, which additionally reports circuit breaker state on the Gateway:

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "accountService": {
          "details": { "state": "CLOSED", ... }
        }
      }
    },
    "db": { "status": "UP" }
  }
}
```

---

## Running the Tests

### All tests (both modules)

From the project root:

```bash
mvn test
```

### Event Gateway tests only

```bash
cd event-gateway
mvn test
```

### Account Service tests only

```bash
cd account-service
mvn test
```

### What the tests cover

| Test class | What it verifies |
|------------|-----------------|
| `EventServiceTest` | Idempotency logic, out-of-order ordering, status transitions |
| `AccountServiceClientTest` | Correct request construction before sending to Account Service |
| `EventApiIntegrationTest` | Full `POST /events` and `GET /events` flows against a real H2 database |
| `IdempotencyIntegrationTest` | Submitting the same `eventId` twice — second call returns `200`, balance unchanged |
| `OutOfOrderIntegrationTest` | Events with older timestamps arriving after newer ones are listed in the correct chronological order |
| `CircuitBreakerTest` | WireMock simulates Account Service failures — verifies circuit opens and `503` is returned; verifies `GET` endpoints still respond |
| `TracePropagationTest` | Asserts that the outbound call to Account Service includes a valid W3C `traceparent` header |
| `AccountServiceTest` | Balance computation with CREDITs and DEBITs in arbitrary order |
| `AccountApiIntegrationTest` | Apply transactions, retrieve balance, list transactions in chronological order |

No external services or databases need to be running to execute the tests — each test uses an in-memory H2 database, and WireMock stubs replace the Account Service where needed.

**Total: 39 tests, 0 failures across both modules.**

---

## Distributed Tracing

Both services use **Micrometer Tracing with the OpenTelemetry bridge** to propagate trace context across service boundaries using the W3C `traceparent` header.

Every inbound request to the Gateway is assigned a `traceId`. When the Gateway calls the Account Service, it constructs the `traceparent` header directly from the current span's `TraceContext`:

```
traceparent: 00-{32-hex traceId}-{16-hex spanId}-{flags}
```

The Account Service reads this header on arrival and creates a child span under the same `traceId`. Both services log `traceId` and `spanId` as top-level JSON fields on every log line (via Logstash Logback Encoder). To correlate a request across both service logs, filter both log streams by the same `traceId`.

**Example log correlation:**

```
# Gateway log
{ "traceId": "abc123...", "spanId": "a1b2c3d4", "message": "Submitting event evt-001" }

# Account Service log — same traceId, different spanId
{ "traceId": "abc123...", "spanId": "e5f6a7b8", "message": "Applying CREDIT 150.00 to acct-123" }
```

Sampling is set to 100% (`management.tracing.sampling.probability: 1.0`). Reduce to `0.1` in high-traffic production environments.

---

## Resiliency Pattern: Circuit Breaker

**Choice:** Resilience4j circuit breaker applied to all Gateway → Account Service calls.

**Why circuit breaker over the alternatives:**

- **Retry with backoff alone** is insufficient when the downstream service is completely down. Retrying repeatedly adds latency to every failed request and can cause a cascade of slow responses that exhaust Gateway threads.
- **Bulkhead alone** limits concurrent calls to prevent resource exhaustion, but it does not give you fast-fail behaviour when the Account Service is consistently unavailable — requests still wait and time out individually.
- **Circuit breaker** monitors the failure rate over a sliding window of recent calls. Once failures cross the configured threshold (50% over 10 calls), the circuit **opens**: all subsequent calls fail immediately with a fallback response, rather than attempting the network call. After a wait period (10 seconds), the circuit moves to **half-open** and allows a small number of probe calls through. If those succeed, the circuit closes and normal operation resumes.

This pattern gives three concrete benefits for this system:

1. **Fast failure** — once the circuit is open, clients receive a `503` in microseconds instead of waiting 3 seconds for each request to time out.
2. **Account Service recovery protection** — a broken Account Service is not hammered with requests during its restart window. The wait period gives it space to recover.
3. **Read path unaffected** — `GET /events/{id}` and `GET /events?account=` are served entirely from the Gateway's local database. The circuit breaker only wraps the write path, so reads remain fully available during an Account Service outage.

**Configured thresholds:**

| Setting | Value |
|---------|-------|
| Sliding window size | 10 calls |
| Failure rate threshold | 50% |
| Wait duration when open | 10 seconds |
| Permitted calls in half-open | 3 |
| Per-call timeout | 3 seconds |

**Observability:** The circuit breaker state (`CLOSED` / `OPEN` / `HALF_OPEN`) is visible in `/actuator/health` under `components.circuitBreakers.details.accountService.details.state`. This requires both `resilience4j.circuitbreaker.instances.accountService.register-health-indicator: true` and `management.health.circuitbreakers.enabled: true` in `application.yml`.

---

## Error Handling

All error responses follow a consistent JSON envelope:

```json
{ "error": "Human-readable message" }
```

For validation failures the response also includes a `details` array listing every field violation:

```json
{
  "error": "Validation failed",
  "details": ["eventId: must not be blank", "amount: must be positive"]
}
```

| Condition | Status |
|-----------|--------|
| Validation failure (`@NotBlank`, `@Positive`, `@Size`) | `400 Bad Request` |
| Malformed or unreadable JSON body | `400 Bad Request` |
| Missing required query parameter | `400 Bad Request` |
| Resource not found (unknown `eventId` or `accountId`) | `404 Not Found` |
| Request to an unmapped path | `404 Not Found` |
| Account Service unreachable or circuit open | `503 Service Unavailable` |
| Unexpected server error | `500 Internal Server Error` |

> **Spring Framework 6.1 note:** The static resource handler raises `NoResourceFoundException` (not the older `NoHandlerFoundException`) when no file matches a requested path. Both exception types are explicitly mapped to `404` in `GlobalExceptionHandler` to prevent them from falling through to the `500` catch-all.

---

## Postman Collection

A ready-to-import Postman collection covering all endpoints across Steps 1–12 is included at the project root:

```
postman-collection.json
```

**To import:** In Postman, go to **File → Import**, select `postman-collection.json`, and click **Import**.

The collection uses three variables (edit under the **Variables** tab after importing):

| Variable | Default | Notes |
|----------|---------|-------|
| `gateway_url` | `http://localhost:9090` | Update port if running on a different port |
| `account_service_url` | `http://localhost:8081` | Update only if Account Service port differs |
| `test_account_id` | `acct-123` | Account ID used across all test scenarios |

**Recommended run order:**
1. `[Steps 1-3] Startup Verification` — confirms both services are responding
2. `Event Gateway → POST /events` — submit `evt-001`, `evt-002`, `evt-003` in order
3. `Event Gateway → GET /events` — verify out-of-order sorting and not-found behaviour
4. `Account Service → POST /accounts/.../transactions` — test Account Service in isolation
5. `Account Service → GET /accounts` — verify balance and sorted history
6. `Event Gateway → GET /health` and `Account Service → GET /health` — custom health endpoints
7. `Event Gateway → GET /actuator` — circuit breaker state, metrics, and tagged counters

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
| `GET` | `/health` | Health check |

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
| `GET` | `/health` | Health check |

### How the Services Interact

1. Client submits `POST /events` to the Gateway.
2. Gateway validates the payload, checks for a duplicate `eventId`, and saves the event locally with status `PENDING`.
3. Gateway calls `POST /accounts/{accountId}/transactions` on the Account Service (wrapped in a circuit breaker).
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

### Verifying Startup

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Both should return:

```json
{ "status": "UP", "database": "UP" }
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
| `TracePropagationTest` | Asserts that the outbound call to Account Service includes a `traceparent` header |
| `AccountServiceTest` | Balance computation with CREDITs and DEBITs in arbitrary order |
| `AccountApiIntegrationTest` | Apply transactions, retrieve balance, list transactions in chronological order |

No external services or databases need to be running to execute the tests — each test uses an in-memory H2 database, and WireMock stubs replace the Account Service where needed.

---

## Resiliency Pattern: Circuit Breaker

**Choice:** Resilience4j circuit breaker with a time limiter, applied to all Gateway → Account Service calls.

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

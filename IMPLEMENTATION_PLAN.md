# Event Ledger — Implementation Plan

## Layer Separation

Each service follows the same layered architecture. Each layer has exactly one responsibility and never reaches past its neighbor.

```
┌─────────────────────────────────────────┐
│  Controller Layer                        │  HTTP only: parse request, call service,
│  (controller/)                           │  map result to response, set status codes
├─────────────────────────────────────────┤
│  Service Layer                           │  Business logic only: idempotency checks,
│  (service/)                              │  balance rules, ordering, orchestration
├─────────────────────────────────────────┤
│  Repository Layer                        │  Data access only: JPA queries, no
│  (repository/)                           │  business logic, no HTTP concerns
├─────────────────────────────────────────┤
│  Model Layer                             │  Two sub-types:
│  (model/)                                │  - Entities: JPA database-mapped classes
│                                          │  - DTOs: request/response shapes (no JPA)
├─────────────────────────────────────────┤
│  Client Layer (Gateway only)             │  Wraps the HTTP call to Account Service.
│  (client/)                               │  Resiliency patterns live here, not in service
├─────────────────────────────────────────┤
│  Config Layer                            │  Spring/library configuration: beans,
│  (config/)                               │  circuit breaker setup, tracing, serialization
└─────────────────────────────────────────┘
```

**Rule**: Controllers never touch repositories. Services never build HTTP responses. Entities never leave the service layer (DTOs cross boundaries, entities do not).

---

## Step 1 — Project Scaffolding

**What:** Maven parent project with two child modules. Both services start up and respond to nothing useful yet.

**Technology versions:**
- Java: 21
- Spring Boot: 3.2.5 (parent POM provides all dependency version management)

**Package structure:**
- Event Gateway: `com.eventledger.gateway`
- Account Service: `com.eventledger.account`

**Files created:**
- `pom.xml` (parent, declares two modules, sets `java.version=21`)
- `event-gateway/pom.xml` — dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `h2` (runtime), `spring-boot-starter-test`
- `account-service/pom.xml` — same dependencies, no shared code with Gateway
- `event-gateway/src/main/java/com/eventledger/gateway/EventGatewayApplication.java`
- `account-service/src/main/java/com/eventledger/account/AccountServiceApplication.java`
- `event-gateway/src/main/resources/application.yml` (port 8080, H2, app name, account-service URL)
- `account-service/src/main/resources/application.yml` (port 8081, H2, app name)

**Key configuration decisions:**

- **H2 database names**: Gateway uses `eventgatewaydb`, Account Service uses `accountservicedb`. Different names make the isolation explicit and each is inspectable independently via the H2 console.
  - Gateway H2 console: `http://localhost:8080/h2-console` → JDBC URL: `jdbc:h2:mem:eventgatewaydb`
  - Account Service H2 console: `http://localhost:8081/h2-console` → JDBC URL: `jdbc:h2:mem:accountservicedb`

- **`ddl-auto: create-drop`**: Schema is derived automatically from `@Entity` classes on startup and dropped on shutdown. No SQL migration files needed for an in-memory database.

- **`account-service.base-url`** in `event-gateway/application.yml` uses an environment variable override:
  ```yaml
  account-service:
    base-url: ${ACCOUNT_SERVICE_BASE_URL:http://localhost:8081}
  ```
  This means the same config file works both locally (falls back to `localhost:8081`) and inside Docker Compose (where the env var overrides to the container hostname) without modification.

- **`logging.level.com.eventledger: DEBUG`** set in both `application.yml` files for visibility during development. Will be replaced with structured JSON logging in Step 9.

**Dependencies intentionally NOT added in this step** (added in the step that first uses them):
- `spring-boot-starter-validation` — added in Step 4
- `spring-boot-starter-actuator` — added in Step 11
- `resilience4j-spring-boot3` — added in Step 10
- `micrometer-tracing-bridge-otel`, `logstash-logback-encoder` — added in Step 9
- WireMock — added in Step 12

**Testable after this step:** Both services start. H2 console accessible at the URLs above. No endpoints yet.

---

## Step 2 — Domain Models (Entities + DTOs)

**What:** Define the shape of data. Entities map to database tables. DTOs are the API contract — they are what enters and leaves the service. No logic anywhere in this step.

**Implementation approach — records vs classes:**
- **DTOs** are implemented as Java **records** — immutable by construction, no boilerplate getters/setters, and concise declarations. Once deserialized, a DTO cannot be mutated as it passes through the layers.
- **Entities** are plain Java **classes** — JPA requires a no-arg constructor (records cannot provide one), and entities need a `setStatus()` setter so the service layer can update lifecycle state without replacing the object.

**Files created:**

*Event Gateway:*
- `model/EventStatus.java` — enum in the `model/` package (not `model/entity/`): `PENDING | PROCESSED | FAILED`. Represents the lifecycle of an event: saved locally (`PENDING`) → Account Service accepted (`PROCESSED`) or unreachable (`FAILED`).
- `model/entity/EventEntity.java` — JPA entity, maps to `events` table. `eventId` is the primary key — a duplicate insert throws `DataIntegrityViolationException`, which the service layer uses for idempotency detection. Only `setStatus()` is provided as a setter; all other fields are set at construction and never change.
- `model/dto/EventRequest.java` — record, inbound `POST /events` payload. No validation annotations yet (added in Step 4). `metadata` is `Map<String, Object>`, null when omitted by the client.
- `model/dto/EventResponse.java` — record, returned by all event endpoints. Includes `status` from `EventStatus` so clients can see whether the transaction was applied.

*Account Service:*
- `model/entity/AccountEntity.java` — JPA entity, maps to `accounts` table. `accountId` is the primary key. Only `setBalance()` and `setLastUpdated()` are mutable — updated together on every transaction.
- `model/entity/TransactionEntity.java` — JPA entity, maps to `transactions` table. `transactionId` (= `eventId` from the Gateway) is the primary key for idempotency. No `@ManyToOne` to `AccountEntity` — the relationship is stored as a plain `accountId` String to avoid lazy-loading complexity.
- `model/dto/TransactionRequest.java` — record, the internal HTTP contract between Gateway and Account Service. Carries `transactionId` (= `eventId`) and `eventTimestamp` propagated from the original event.
- `model/dto/TransactionResponse.java` — record, confirms the transaction was applied and reports the new balance.
- `model/dto/AccountResponse.java` — record, returned by `GET /accounts/{id}`. Contains a `List<TransactionDetail>` where `TransactionDetail` is a **nested record** inside `AccountResponse`. It is nested because it only ever appears in this context — a separate top-level file would add a class with no independent use. `accountId` is omitted from `TransactionDetail` because it is already present at the parent `AccountResponse` level.
- `model/dto/BalanceResponse.java` — record, returned by `GET /accounts/{id}/balance`. Contains only `accountId`, `balance`, and `currency`.

**Database column decisions:**
- `amount` and `balance` fields use `@Column(precision = 15, scale = 2)` → `DECIMAL(15,2)` in H2. Consistent across both services so values round-trip without precision loss.
- `metadata` in `EventEntity` is `@Column(columnDefinition = "TEXT")` — JPA has no native `Map<String,Object>` type. The field is stored as a raw JSON string. The service layer (Step 5) is responsible for serializing `Map<String,Object> → String` on write and deserializing `String → Map<String,Object>` on read.

**Jackson configuration added to both `application.yml` files in this step:**
- `spring.jackson.serialization.write-dates-as-timestamps: false` — `Instant` fields serialize as ISO-8601 strings (e.g. `"2026-05-15T14:02:11Z"`) not epoch-second numbers. Requires Jackson's `JavaTimeModule`, which Spring Boot auto-configures.
- `spring.jackson.default-property-inclusion: non_null` — null fields (e.g. `metadata` when absent) are omitted from JSON responses rather than serialized as `null`.

**Why entities and DTOs are separate:** Entities carry JPA annotations and are tied to the database schema. If you return entities directly from controllers, you couple your API contract to your database schema — a change to one breaks the other. DTOs decouple them.

**Testable after this step:** H2 auto-creates tables from entities on startup. Schema visible in H2 console with correct column types and constraints.

---

## Step 3 — Repository Layer

**What:** Spring Data JPA interfaces. Custom queries for ordering and filtering. No business logic — only data access.

**Inherited vs custom methods:**
All repositories extend `JpaRepository<Entity, String>`. Where the plan listed a method whose logic is already covered by an inherited `JpaRepository` method, the inherited version is used instead to avoid redundant declarations:

| Plan listed | Actually used | Reason |
|---|---|---|
| `findByEventId(String id)` | inherited `findById(String id)` | `eventId` is the `@Id` field — derived query and PK lookup are identical |
| `existsByTransactionId(String id)` | inherited `existsById(String id)` | `transactionId` is the `@Id` field — same reasoning |

Custom queries are only declared when the inherited methods cannot express the intent (ordering, aggregation).

**Files created:**

*Event Gateway:*
- `repository/EventRepository.java`
  - **Inherited:** `findById(String eventId)` — retrieve by PK; returns `Optional<EventEntity>`
  - **Inherited:** `save(EventEntity)` — insert or update; duplicate PK throws `DataIntegrityViolationException`
  - **Custom (derived):** `findByAccountIdOrderByEventTimestampAsc(String accountId)` — account-scoped listing sorted by `eventTimestamp ASC`, not by insertion order

*Account Service:*
- `repository/AccountRepository.java`
  - All operations covered by inherited methods: `findById`, `save`, `existsById`
  - No custom queries needed — balance and history live in `TransactionRepository`
- `repository/TransactionRepository.java`
  - **Inherited:** `save(TransactionEntity)` — insert a new transaction
  - **Inherited:** `existsById(String transactionId)` — idempotency check before applying; fast PK lookup
  - **Custom (derived):** `findByAccountIdOrderByEventTimestampAsc(String accountId)` — chronological transaction history
  - **Custom (`@Query`):** `sumAmountByAccountIdAndType(String accountId, String type)` — JPQL SUM used to compute balance:
    ```sql
    SELECT COALESCE(SUM(t.amount), 0)
    FROM TransactionEntity t
    WHERE t.accountId = :accountId AND t.type = :type
    ```
    `COALESCE(..., 0)` returns `BigDecimal.ZERO` instead of `null` when no transactions of that type exist yet. The service calls this twice — once for `"CREDIT"`, once for `"DEBIT"` — and subtracts to get the net balance.

**Why `@Query` is needed for balance:** Spring Data cannot derive an aggregation (`SUM`) from a method name alone. Derived query names only support filtering and ordering — not aggregate functions.

**Testable after this step:** `@DataJpaTest` tests — insert rows with different `eventTimestamp` values out of insertion order, assert the returned list is sorted by `eventTimestamp ASC`. Insert two transactions and assert `sumAmountByAccountIdAndType` returns the correct total.

---

## Step 4 — Input Validation and Error Handling

**What:** Bean Validation constraints on `EventRequest`. A single `GlobalExceptionHandler` that intercepts all errors and converts them into a consistent JSON error format. No business logic.

**Dependency added in this step:**
- `spring-boot-starter-validation` added to `event-gateway/pom.xml` — this is a separate artifact in Spring Boot 3.x and is not bundled with `spring-boot-starter-web`. It provides `@Valid`, `@Validated`, `@NotBlank`, `@NotNull`, `@Positive`, `@Pattern`, `@Size`, `MethodArgumentNotValidException`, and `ConstraintViolationException`.

**Files created/modified:**

*Event Gateway:*
- `model/dto/EventRequest.java` — validation annotations added to every component of the record
- `model/dto/ErrorResponse.java` — standard error shape: `{ "error": "...", "details": [...] }`
- `exception/GlobalExceptionHandler.java` — maps all exception types to HTTP responses

**Validation constraints on `EventRequest`:**

| Field | Constraints | Key decision |
|---|---|---|
| `eventId` | `@NotBlank` | — |
| `accountId` | `@NotBlank` | — |
| `type` | `@NotBlank` + `@Pattern(regexp = "^(CREDIT\|DEBIT)$")` | Pattern is anchored with `^` and `$` — without anchors, values like `"CREDIT_EXTRA"` would satisfy the regex by partial match |
| `amount` | `@NotNull` + `@Positive` | Both are required: `@Positive` silently passes `null` without failing, so `@NotNull` must be alongside it to catch a missing field |
| `currency` | `@NotBlank` + `@Size(min=3, max=3)` | ISO 4217 codes are always exactly 3 characters |
| `eventTimestamp` | `@NotNull` | Format validation is handled by Jackson before constraints run — a malformed ISO-8601 string throws `HttpMessageNotReadableException` first |
| `metadata` | none | Optional field; absence is valid |

**`GlobalExceptionHandler` — exception types handled:**

| Exception | HTTP status | When thrown |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `@Valid` fails on `@RequestBody` — collects all field violations so the client receives a complete list |
| `ConstraintViolationException` | 400 | `@Validated` on class + constraints on method parameters (path vars, query params) |
| `HttpMessageNotReadableException` | 400 | Jackson cannot deserialise the body (wrong type for a field, broken JSON syntax, empty body). Internal Jackson message is **not** forwarded to the client to avoid leaking class names |
| `MissingServletRequestParameterException` | 400 | Required `@RequestParam` absent — e.g. `GET /events` without `?account=` |
| `IllegalArgumentException` | 400 | Business rule violations thrown by the service layer |
| `NoSuchElementException` | 404 | `Optional.orElseThrow(...)` in service methods when entity not found |
| `Exception` (catch-all) | 500 | Anything unhandled — full stack trace logged at ERROR, generic message returned to client |

**`ErrorResponse` design:**
- `details` field is `null` (not an empty list) when absent — combined with `spring.jackson.default-property-inclusion=non_null`, a null `details` field is simply omitted from JSON output. The client never sees `"details": null`.
- Convenience constructor `ErrorResponse(String error)` sets `details` to null for non-validation errors.

**Logging levels in `GlobalExceptionHandler`:**
- `DEBUG` — validation failures (400), not-found (404): high-volume, expected, not actionable
- `WARN` — `IllegalArgumentException`: unexpected business rule violation
- `ERROR` — unhandled exceptions: always worth investigation

**Why a central exception handler:** Controllers contain no try/catch blocks. All exception-to-HTTP mapping lives in one place, controllers stay clean, and adding a new error condition means editing only `GlobalExceptionHandler`.

**Testable after this step:** Unit tests on `EventRequest` validation constraints, MockMvc tests verifying each exception type produces the correct HTTP status and `ErrorResponse` shape.

---

## Step 5 — Event Gateway Core API (Account Service not yet wired)

**What:** The three read endpoints plus the POST endpoint — but POST only saves the event locally. The Account Service call is stubbed with a placeholder that always returns success. This lets the Gateway's own logic be tested in isolation.

**Files created:**

*Event Gateway:*
- `exception/AccountServiceUnavailableException.java` — runtime exception defined now so `EventService` can reference it; the stub never throws it. Added to `GlobalExceptionHandler` as 503 in Step 10.
- `client/AccountServiceClient.java` — stub: no-op method, always returns normally. Method signature is fixed here so the service layer is unaffected when the real implementation replaces it in Step 7.
- `service/EventService.java`
  - `submitEvent(EventRequest)` → returns `EventResponse`
  - `getEventById(String eventId)` → returns `EventResponse`, throws `NoSuchElementException` if not found
  - `getEventsByAccount(String accountId)` → returns `List<EventResponse>`, empty list if none
- `controller/EventController.java`
  - `POST /events` → `@Valid @RequestBody EventRequest` → 201 Created (200 OK for duplicates added in Step 8)
  - `GET /events/{eventId}` → 200 OK or 404
  - `GET /events?account=` → 200 OK with list (empty array if no events, never 404)

**Key design decisions:**

**`submitEvent` transaction strategy — no `@Transactional` on the method:**
Spring Data's `save()` runs in its own short transaction. This is intentional: if the method were wrapped in a single `@Transactional`, an exception from the Account Service call would roll back the entire method, reverting the FAILED status update and losing the event record. The two-save pattern instead guarantees:
- First `save()` commits PENDING before the Account Service call — event is always persisted.
- Second `save()` commits PROCESSED or FAILED after the call — audit trail is always complete.

Read methods use `@Transactional(readOnly = true)` to allow the JPA provider to skip dirty-checking.

**Metadata serialisation:**
`ObjectMapper` is injected as a constructor dependency (Spring Boot auto-configures it with the `application.yml` settings). Metadata is serialised `Map<String,Object> → JSON string` on write and deserialised in reverse on read, inside private helper methods. Both helpers guard against `JsonProcessingException` with a warn log but no throw — a `Map` constructed from Jackson-parsed input can always round-trip without error.

**Controller has no try/catch — error flow:**
- `@Valid` triggers validation before the method body runs. Failures throw `MethodArgumentNotValidException` → `GlobalExceptionHandler` → 400.
- `NoSuchElementException` from service → `GlobalExceptionHandler` → 404.
- `AccountServiceUnavailableException` from service → `GlobalExceptionHandler` (Step 10) → 503.
- Missing `?account=` parameter → `MissingServletRequestParameterException` → `GlobalExceptionHandler` → 400.

**Response code for POST /events:**
Returns 201 Created in this step. Step 8 (idempotency) changes the response to 200 OK for duplicate submissions. The controller signature (`submitEvent` returns `EventResponse`) will be updated in Step 8 to carry a `isDuplicate` flag via a wrapper result type.

**Testable after this step:**
- `POST /events` with valid payload → 201, event stored, status PROCESSED
- `POST /events` with invalid payload → 400 with field-level error details
- `GET /events/{eventId}` → 200 with event; unknown id → 404
- `GET /events?account=` → 200 with chronologically ordered list; empty account → `[]`
- Submit three events with out-of-order timestamps, verify list returns them sorted by `eventTimestamp` not arrival order

---

## Step 6 — Account Service Core API

**What:** Account Service built standalone. Can apply transactions, compute balance, and return account details — all independent of the Gateway. Balance computation handles out-of-order naturally because it is a SUM, not a running total that depends on insertion order.

**Files created:**

*Account Service:*
- `service/AccountService.java`
  - `applyTransaction(String accountId, TransactionRequest)` — creates account if new, inserts transaction, returns updated balance
  - `getBalance(String accountId)` — queries `SUM` of credits and debits
  - `getAccount(String accountId)` — account details + transaction list ordered by `eventTimestamp`
- `controller/AccountController.java`
  - `POST /accounts/{accountId}/transactions`
  - `GET /accounts/{accountId}/balance`
  - `GET /accounts/{accountId}`

**Why balance is a SUM query and not a running total:** If a DEBIT arrives for `2026-05-01` after a CREDIT for `2026-05-02`, the balance must still be correct. A SUM over all transactions is commutative — insertion order is irrelevant.

**Testable after this step:**
- Apply CREDIT then DEBIT → correct balance
- Apply DEBIT then CREDIT → same correct balance (out-of-order proof)
- Account auto-created on first transaction

---

## Step 7 — Service Integration (Gateway calls Account Service for real)

**What:** Replace the stub `AccountServiceClient` with a real `RestTemplate`-based implementation. Define the full request/response contract between the two services. Basic error handling for HTTP failures (non-2xx responses).

**Files modified:**
- `client/AccountServiceClient.java` — real implementation using `RestTemplate`
- `config/RestTemplateConfig.java` — `RestTemplate` bean; reads `account-service.base-url` from `application.yml` (this property was already added in Step 1 — nothing new to configure here)

**What crosses the wire:** `TransactionRequest` (eventId, type, amount, currency, eventTimestamp) → Account Service returns `TransactionResponse` (transactionId, accountId, newBalance).

**Testable after this step:** Full `POST /events` flow — Gateway saves event, Account Service applies transaction, Gateway updates event status to `PROCESSED`. Integration test covering both services together.

---

## Step 8 — Idempotency (both services)

**What:** Two-layer protection against duplicate event submission.

**Gateway layer:** Before saving, query by `eventId`. If found, return the existing event with HTTP `200` (not `201`). No call to Account Service.

**Account Service layer:** Before applying, check if `transactionId` already exists in `transactions` table. If yes, return the existing result without modifying the balance.

**Files modified:**
- `service/EventService.java` — add duplicate check before save
- `service/AccountService.java` — add `existsByTransactionId` check before insert

**Why both layers need it:** The Gateway catches 99% of duplicates cheaply. The Account Service protects against the edge case where the Gateway saved the event but crashed before marking it processed, then retried.

**Testable after this step:**
- Submit same `eventId` twice — second call returns `200` with original event, balance unchanged
- Verify Account Service receives only one transaction

---

## Step 9 — Distributed Tracing

**What:** Every request through the Gateway gets a `traceId`. It is propagated to the Account Service via the W3C `traceparent` header. Both services include the `traceId` in every log line.

**Dependencies added:** `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-logging`, `logstash-logback-encoder`

**Files created/modified:**
- `event-gateway/src/main/resources/logback-spring.xml` — JSON log format including `traceId`, `spanId`, `service`, `level`, `timestamp`
- `account-service/src/main/resources/logback-spring.xml` — same
- `config/TracingConfig.java` (both services) — configure OTEL exporter, sampler

**How propagation works:** Micrometer Tracing auto-instruments `RestTemplate` beans. When `AccountServiceClient` makes a call, the `traceparent` header is added automatically. Account Service reads it automatically and creates a child span under the same trace.

**Testable after this step:** Submit one event, check logs from both services — same `traceId` appears in both log streams.

---

## Step 10 — Resiliency (Circuit Breaker)

**What:** Resilience4j circuit breaker wrapping the Account Service call. When the Account Service repeatedly fails, the circuit opens and subsequent calls fail immediately with a fallback response. Gateway returns `503` — not `500`, not a hang.

**Dependencies added:** `resilience4j-spring-boot3`, `resilience4j-circuitbreaker`, `resilience4j-timelimiter`

**Files created/modified:**
- `config/ResilienceConfig.java` (Gateway) — circuit breaker bean, failure rate threshold, wait duration, timeout
- `client/AccountServiceClient.java` — `@CircuitBreaker(name = "accountService", fallbackMethod = "fallback")` on the call method, fallback throws `AccountServiceUnavailableException`
- `exception/GlobalExceptionHandler.java` — maps `AccountServiceUnavailableException` to `503`
- `event-gateway/src/main/resources/application.yml` — resilience4j configuration block

**Circuit breaker settings:**

| Setting | Value | Reason |
|---|---|---|
| `slidingWindowSize` | 10 | Evaluate over last 10 calls |
| `failureRateThreshold` | 50% | Open if half or more fail |
| `waitDurationInOpenState` | 10s | Stay open 10 seconds |
| `permittedCallsInHalfOpen` | 3 | Probe 3 calls before fully closing |
| `timeoutDuration` | 3s | Don't wait more than 3s per call |

**Testable after this step:**
- Stop Account Service → POST /events returns 503
- GET /events/{id} and GET /events?account= still return data (Gateway-only)
- Restart Account Service after 10s → circuit closes, POST /events works again

---

## Step 11 — Observability (Health Checks + Metrics)

**What:** Health endpoints on both services that actively probe the database. Custom Micrometer metrics: event submission counter and Account Service call timer.

**Dependencies added in this step:**
- `spring-boot-starter-actuator` added to both `event-gateway/pom.xml` and `account-service/pom.xml` — provides the `/actuator/metrics` endpoint and the Micrometer `MeterRegistry` used by `MetricsService`.

**Files created:**

*Both services:*
- `controller/HealthController.java` — `GET /health`, runs `SELECT 1` against H2, returns `{ "status": "UP|DOWN", "database": "UP|DOWN", "service": "...", "timestamp": "..." }`

*Event Gateway:*
- `service/MetricsService.java` — wraps Micrometer `Counter` (events by type/status) and `Timer` (Account Service call duration)

**Testable after this step:** `GET /health` on both services returns correct status. Metrics visible at `/actuator/metrics/events.submitted`.

---

## Step 12 — Tests

**What:** Full test coverage. Each test category isolated to its own class.

**Files created:**

```
event-gateway/test/
  unit/
    EventServiceTest.java           - idempotency, ordering, validation logic
    AccountServiceClientTest.java   - verify correct request construction
  integration/
    EventApiIntegrationTest.java        - full POST/GET flows with real H2
    IdempotencyIntegrationTest.java     - duplicate submission scenarios
    OutOfOrderIntegrationTest.java      - submit older-timestamped events last
    CircuitBreakerTest.java             - WireMock simulates Account Service failure
    TracePropagationTest.java           - verify traceparent header is sent

account-service/test/
  unit/
    AccountServiceTest.java             - balance computation, out-of-order proofs
  integration/
    AccountApiIntegrationTest.java      - full transaction apply/balance/list flows
```

**Dependency added in this step:**
- WireMock added to `event-gateway/pom.xml` (test scope): `org.springframework.cloud:spring-cloud-contract-wiremock` — provides an embedded WireMock server for simulating Account Service responses in `CircuitBreakerTest` and `TracePropagationTest`.

**Tooling:** `@SpringBootTest` for integration, `@DataJpaTest` for repository, `WireMock` for simulating Account Service, `MockMvc` for controller tests.

---

## Summary Table

| Step | What it builds | Testable outcome |
|---|---|---|
| 1 | Project scaffolding | Both services start |
| 2 | Entities + DTOs | H2 schema created on startup |
| 3 | Repositories | Data access queries verified |
| 4 | Validation + error handling | 400 responses with clear messages |
| 5 | Gateway core API (stubbed client) | Submit/retrieve/list events, out-of-order ordering |
| 6 | Account Service core API | Balance computation, out-of-order balance correctness |
| 7 | Real service integration | Full Gateway → Account Service flow |
| 8 | Idempotency | Duplicate submissions handled at both layers |
| 9 | Distributed tracing | Same traceId in logs from both services |
| 10 | Circuit breaker | 503 on Account Service failure, reads still work |
| 11 | Health + metrics | /health endpoint, metrics counter/timer |
| 12 | Tests | All scenarios covered with runnable test suite |

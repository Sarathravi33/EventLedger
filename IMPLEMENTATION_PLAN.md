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

**What:** Account Service built standalone. Can apply transactions, compute balance, and return account details — all independent of the Gateway. Balance computation handles out-of-order naturally because addition and subtraction are commutative: the result is the same regardless of the order operations are applied.

**Files created:**

*Account Service:*
- `model/dto/ErrorResponse.java` — same shape as Gateway's (`error` + nullable `details`). `details` is always null here (no Bean Validation in this service), so Jackson omits it from all responses.
- `exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` with three handlers: `NoSuchElementException` → 404, `IllegalArgumentException` → 400, `Exception` → 500.
- `service/AccountService.java`
  - `applyTransaction(String accountId, TransactionRequest)` — auto-creates account, inserts transaction, updates running balance, returns `TransactionResponse`
  - `getBalance(String accountId)` — reads running total from `AccountEntity.balance` (O(1))
  - `getAccount(String accountId)` — account row + full transaction history ordered by `eventTimestamp ASC`
- `controller/AccountController.java`
  - `POST /accounts/{accountId}/transactions` → 201 Created
  - `GET /accounts/{accountId}/balance` → 200 / 404
  - `GET /accounts/{accountId}` → 200 / 404

**Key design decisions:**

**Balance as running total, not SUM-on-read:**
`AccountEntity.balance` is maintained as a running total updated atomically in every `applyTransaction` call:
- `CREDIT` → `balance += amount`
- `DEBIT`  → `balance -= amount`

`getBalance` reads this stored value directly — O(1), no aggregation query at read time. This is correct under out-of-order delivery because addition and subtraction are commutative: the final balance is identical regardless of the order transactions arrived. The `sumAmountByAccountIdAndType` repository method exists but is not used in the primary read path — it is available for verification in tests (Step 12).

**`applyTransaction` is `@Transactional`:**
Unlike `EventService.submitEvent` (which deliberately omits `@Transactional`), `applyTransaction` wraps both the transaction insert and the balance update in a single database transaction. If either save fails, both are rolled back — no partial state is ever committed. There is no downstream HTTP call inside this method, so there is no risk of the transaction being held open across a network boundary.

**Account auto-creation via `orElseGet` + `save()`:**
`accountRepository.findById(accountId).orElseGet(...)` returns a transient `AccountEntity` if the account does not exist. The final `accountRepository.save(account)` at the end of the method handles both cases: JPA's `merge` inserts the row if the PK is absent from the database, or updates it if present.

**Switch expression for type dispatch:**
```java
BigDecimal newBalance = switch (request.type()) {
    case "CREDIT" -> account.getBalance().add(request.amount());
    case "DEBIT"  -> account.getBalance().subtract(request.amount());
    default -> throw new IllegalArgumentException("Unknown transaction type: " + request.type());
};
```
The `default` branch throws `IllegalArgumentException` → `GlobalExceptionHandler` → 400. In practice this path is unreachable because the Gateway validated the type upstream.

**No `@Valid` on `AccountController`:**
This is an internal API called only by the Gateway. The Gateway's `GlobalExceptionHandler` validated the event before forwarding it to the Account Service. Adding `@Valid` here would require adding `spring-boot-starter-validation` to `account-service/pom.xml` for no real benefit — the defence-in-depth belongs at the system boundary (the Gateway), not on the internal service.

**`ErrorResponse` and `GlobalExceptionHandler` added (not in original plan):**
The original plan omitted these for Step 6, but without them Spring returns its Whitelabel error format for 404s — useless to the Gateway client. A minimal handler was added alongside the service and controller.

**Testable after this step:**
- `POST /accounts/{id}/transactions` with CREDIT, then DEBIT → `GET /accounts/{id}/balance` shows correct balance
- Submit DEBIT then CREDIT for same amounts → same balance (out-of-order proof)
- Account auto-created on first transaction — no setup call needed
- `GET /accounts/{unknown}` → 404 with `{ "error": "Account not found: ..." }`

---

## Step 7 — Service Integration (Gateway calls Account Service for real)

**What:** Replace the stub `AccountServiceClient` with a real `RestTemplate`-based implementation. Gateway now makes a live HTTP call to the Account Service on every `POST /events`. Basic error handling converts all failures into `AccountServiceUnavailableException`.

**Files created/modified:**
- `config/RestTemplateConfig.java` — new `@Configuration` class declaring the `RestTemplate` bean
- `client/AccountServiceClient.java` — stub body replaced with real `RestTemplate` call; method signature unchanged so `EventService` required no edits

**What crosses the wire:**
- **Request:** `POST {baseUrl}/accounts/{accountId}/transactions` with JSON body: `transactionId`, `type`, `amount`, `currency`, `eventTimestamp`
- **Response:** 2xx indicates success; response body is not read by the Gateway (`Void.class`)

**Key design decisions:**

**`RestTemplateBuilder` not `new RestTemplate()`:**
Spring Boot auto-configures a `RestTemplateBuilder` that applies the application's `HttpMessageConverters`, including the `MappingJackson2HttpMessageConverter` backed by the configured `ObjectMapper`. Using the builder ensures the same Jackson settings apply on the wire:
- `write-dates-as-timestamps: false` → `Instant` fields serialise as ISO-8601 strings
- `default-property-inclusion: non_null` → null fields are omitted

A plain `new RestTemplate()` creates its own default `ObjectMapper` and ignores `application.yml` settings entirely — `Instant` would serialise as a timestamp array, breaking `eventTimestamp` deserialization in the Account Service.

**Gateway defines its own wire contract — no shared module:**
The two services share no code. The Account Service's `TransactionRequest` lives in `account-service` and is not on the Gateway's classpath. A private `TransactionRequestBody` record inside `AccountServiceClient` mirrors the same JSON field names without any cross-module dependency. Keeping the services independent means either can change its internal types without affecting the other, as long as the JSON field names stay in sync.

**`Void.class` response type:**
`restTemplate.postForEntity(url, body, Void.class)` — the Gateway does not need the Account Service's response body. The only signal it needs is "did the call succeed?". A 2xx response causes the method to return normally; any failure throws. Reading `newBalance` from the response is not needed because the Gateway's `EventResponse` does not expose a balance field — balance queries go directly to `GET /accounts/{id}/balance`.

**Two distinct exception catches:**

| Exception | Cause | Action |
|---|---|---|
| `ResourceAccessException` | Network failure: connection refused, host unreachable, read timeout | Throw `AccountServiceUnavailableException` with cause |
| `HttpStatusCodeException` | Account Service returned non-2xx (4xx or 5xx) | Throw `AccountServiceUnavailableException` with status code in message |

Both are mapped to the same `AccountServiceUnavailableException` so `EventService` (and later the circuit breaker in Step 10) sees a single exception type regardless of the failure mode.

**`account-service.base-url` injected via `@Value`:**
The property was added in Step 1. `@Value("${account-service.base-url}")` on the constructor parameter binds it at startup. The default `http://localhost:8081` applies locally; overriding with `ACCOUNT_SERVICE_BASE_URL` env var covers Docker Compose and other environments without changing configuration files.

**Testable after this step:**
- Start both services; `POST /events` → 201, Account Service applies transaction, Gateway status = PROCESSED
- Stop Account Service; `POST /events` → event saved as FAILED (status visible on `GET /events/{id}`), currently returns 500 (503 added in Step 10)
- `GET /events/{id}` and `GET /events?account=` still work with Account Service down (Gateway-only reads)

---

## Step 8 — Idempotency (both services)

**What:** Two-layer protection against duplicate event submission. The Gateway catches duplicates before any write; the Account Service catches them before any balance modification. Together they ensure exactly-once semantics even when the network or the Gateway process fails mid-flight.

**Files created/modified:**
- `model/dto/EventSubmitResult.java` (Gateway, new) — internal wrapper: `record EventSubmitResult(EventResponse event, boolean duplicate)`. Never serialised; only used between service and controller.
- `service/EventService.java` (Gateway) — `submitEvent` return type changed to `EventSubmitResult`; `findById` check added as first step
- `controller/EventController.java` (Gateway) — unwraps `EventSubmitResult` to choose 201 vs 200
- `service/AccountService.java` (Account Service) — `existsById` check added as first step of `applyTransaction`

**Gateway layer — how it works:**
`submitEvent` calls `eventRepository.findById(eventId)` before any write. If the row already exists (with any status: PENDING, PROCESSED, or FAILED), the existing entity is returned immediately:
- No metadata serialisation
- No `EventEntity` construction or save
- No Account Service call
- Returns `EventSubmitResult(existingEvent, duplicate=true)` → controller returns **200 OK**

New events follow the existing two-save flow and return `EventSubmitResult(newEvent, duplicate=false)` → controller returns **201 Created**.

**`EventSubmitResult` instead of a flag on `EventResponse`:**
Adding a `duplicate` field to `EventResponse` would leak an internal concern into the public API contract. The wrapper keeps the signal internal — the controller consumes it to pick the status code, and the client receives a plain `EventResponse` body either way. Clients that only care about the data can ignore the status code entirely.

**Account Service layer — how it works:**
`applyTransaction` calls `transactionRepository.existsById(transactionId)` before any write. If the row already exists, the current `AccountEntity.balance` is read and returned without modifying anything:
- No `TransactionEntity` insert
- No balance delta applied
- Returns `TransactionResponse` with the current (unchanged) balance

**Why the Account Service needs its own layer:**
The Gateway's check covers the common case (client retrying a failed HTTP call). It does not cover the case where:
1. Gateway saves PENDING ✓
2. Account Service applies the transaction ✓
3. Account Service returns 201 ✓
4. Gateway crashes before saving PROCESSED ✗
5. Gateway restarts and retries → without Account Service idempotency, the balance would be applied twice

The Account Service's `existsById` check makes the overall system safe against this scenario.

**Why `existsById` (not `findById`) for the Account Service check:**
`existsById` is a `SELECT COUNT(*)` or `SELECT 1` with a `LIMIT 1` — it returns only a boolean and avoids loading the full entity when a duplicate is detected. `findById` would load the entity unnecessarily in the happy path (no duplicate), adding overhead to every call. Only on the duplicate path (uncommon) do we load the account to get the current balance.

**PK constraint as safety net:**
The `existsById` + `save` pattern has a TOCTOU (time-of-check to time-of-use) race: two concurrent requests with the same ID can both pass the `existsById` check before either inserts. The primary key constraint on `transactions` catches this — one insert succeeds, the other throws `DataIntegrityViolationException`. This is acceptable for this exercise; production systems would use `INSERT ... ON CONFLICT` or a SELECT-FOR-UPDATE lock.

**Testable after this step:**
- Submit same `eventId` twice → second response is 200 OK with identical body; `GET /accounts/{id}/balance` unchanged
- Submit same `eventId` after a FAILED event → returns the FAILED event as-is (no retry to Account Service)
- Directly POST the same `transactionId` twice to Account Service → second call returns current balance, no double-apply

---

## Step 9 — Distributed Tracing

**What:** Every request through the Gateway gets a `traceId`. It is propagated to the Account Service via the W3C `traceparent` header. Both services log every line as a JSON object that includes `traceId` and `spanId`, making it possible to follow a single event across both service log streams.

**Dependencies added (both poms):**

| Dependency | Why |
|---|---|
| `io.micrometer:micrometer-tracing-bridge-otel` | Creates spans per request, writes `traceId`/`spanId` to the MDC, propagates W3C `traceparent` on `RestTemplate` calls. Version managed by Spring Boot BOM. |
| `org.springframework.boot:spring-boot-starter-actuator` | **Required prerequisite** — Spring Boot's tracing auto-configuration (`OpenTelemetryAutoConfiguration`, `TracingProperties`) lives in `spring-boot-actuator-autoconfigure`. Without actuator on the classpath the bridge is present but no `Tracer` bean is created and no MDC population happens. Also enables `/actuator/health` and `/actuator/metrics` used in Step 11. |
| `net.logstash.logback:logstash-logback-encoder:7.4` | Formats each log event as one JSON object. All MDC entries — including `traceId` and `spanId` — appear automatically as top-level JSON fields. Version `7.4` is compatible with Logback 1.4.x (Spring Boot 3.2.x). |

**Note on `spring-boot-starter-actuator`:** Originally planned for Step 11. Pulled forward here because it is a hard prerequisite for tracing auto-configuration. Step 11 still creates `HealthController` and `MetricsService`, but the dependency is already on the classpath.

**`opentelemetry-exporter-logging` omitted:** The original plan included this OTel span exporter. It was dropped — span export produces a separate log line per completed span (useful for distributed tracing dashboards) but is noise for the development goal of correlating log lines by `traceId`. The MDC-based approach via `logstash-logback-encoder` achieves that goal without the extra dependency.

**No `TracingConfig.java`:** The original plan included this. Everything needed is fully auto-configured:
- `Tracer` bean — Spring Boot creates it from `micrometer-tracing-bridge-otel` + actuator
- Sampling rate — set via `management.tracing.sampling.probability: 1.0` in `application.yml`
- `RestTemplate` instrumentation — applied by `RestTemplateBuilder` (already used in `RestTemplateConfig`)
- MDC population — done by Micrometer Tracing on every incoming request

**Files created/modified:**

*Both services:*
- `pom.xml` — three new dependencies (above)
- `src/main/resources/application.yml` — added `management.tracing.sampling.probability: 1.0`; removed `logging.level` block (log levels now owned by `logback-spring.xml`)
- `src/main/resources/logback-spring.xml` (new) — `LogstashEncoder` appender; `com.eventledger` at DEBUG, root at INFO; `service` custom field from `spring.application.name`

**How traceId flows end-to-end:**
1. `POST /events` arrives at the Gateway
2. Micrometer Tracing creates a root span; writes `traceId` and `spanId` to the MDC
3. Every Gateway log statement for this request includes `traceId` via `LogstashEncoder`
4. `AccountServiceClient` calls Account Service via the instrumented `RestTemplate` — adds header: `traceparent: 00-{traceId}-{spanId}-01`
5. Account Service receives the header; Micrometer Tracing creates a child span under the same `traceId`; writes to its own MDC
6. Every Account Service log statement for this request includes the **same** `traceId`

**JSON log line shape:**
```json
{
  "@timestamp": "2026-06-12T10:00:00.000Z",
  "level": "DEBUG",
  "logger_name": "c.e.gateway.service.EventService",
  "message": "Saved event evt-001 with status PENDING",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "service": "event-gateway",
  "thread_name": "http-nio-8080-exec-1"
}
```

**Testable after this step:**
- `POST /events` → check Gateway logs for a `traceId` field on every line
- Check Account Service logs for the **same** `traceId` on the lines logged while handling that request
- No code change to `AccountServiceClient` — `RestTemplateBuilder` instrumentation handled propagation automatically

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

**Dependencies:** `spring-boot-starter-actuator` was added to both poms in Step 9 (required for tracing auto-configuration). No new dependencies in this step — `MeterRegistry` is provided by the actuator already on the classpath.

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

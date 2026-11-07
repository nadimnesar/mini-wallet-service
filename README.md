# Mini Wallet Service

A high-concurrency, ACID-compliant digital wallet backend service modeled after **Pathao Pay**. The service manages digital wallets, wallet transactions, and peer-to-peer (P2P) money transfers with strict money invariants, ascending-order locking to prevent deadlocks, and idempotency guarantees.

---

## 1. Architecture Overview

The project is structured as a Maven multi-module architecture with compiler-enforced layering:

* **`common`**: Shared utilities, error codes, and base domain exceptions.
* **`models`**: Pure domain models, value objects, and API Data Transfer Objects (DTOs).
* **`repository`**: All repositories: data access interfaces and their JDBC implementations.
* **`clients`**: Clients for external systems: the PostgreSQL database (connection, read/write `DataSourceConfig`, Liquibase schema) and the Visa/Mastercard payment gateway.
* **`services`**: Core business domain logic, financial orchestration, and transaction handling.
* **`middleware`**: Runnable Spring Boot application module containing REST controllers, filters, request validation, and application configuration. Only this module produces an executable fat JAR.

---

## 2. Requirements & Setup

### Prerequisites
* Java 25 or higher
* Docker & Docker Compose
* Maven 3.9+ (or use the included `./mvnw` wrapper)

### Running the Application

To start PostgreSQL and the application with a single command:
```bash
docker compose up
```

#### Database

`docker compose up` starts a single PostgreSQL 18 instance (`postgres`, host port `5432`) next to the app, RabbitMQ and the payment-gateway mock.
Connection settings are under `spring.datasource.*` in `application.properties`; override them with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
After editing an applied Liquibase changeset, or when the PostgreSQL major version changes, reset the data with `docker compose down -v`.

#### Health

Actuator exposes only the health probes (no other actuator endpoint, no component details): `GET /actuator/health/liveness` (the process is up) and `GET /actuator/health/readiness` (the database answers). Readiness deliberately ignores RabbitMQ: with the broker down the service still accepts add-money (the outbox keeps the request), so it should stay in rotation. `docker compose` uses the readiness probe as the `app` healthcheck.

#### RabbitMQ

`docker compose up` also starts RabbitMQ 4 (`rabbitmq`, AMQP on host port `5672`, management UI on <http://localhost:15672>, user and password `miniwallet`). Add-money is asynchronous and needs it. Override the connection with `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD`; the retry settings are under `miniwallet.messaging.add-money.*` in `application.properties`.

Alternatively, to build and run the executable module locally:
```bash
./mvnw clean package
./mvnw spring-boot:run -pl middleware
```

---

## 3. Running Tests

To run the complete automated test suite across all modules:
```bash
./mvnw clean test
```

Docker must be running. The integration tests in `middleware` (packages `ledger`, `service` and `transaction`: ledger constraints, wallets, add-money, transfers, history and health) use Testcontainers to start a real PostgreSQL (`postgres:18-alpine`), RabbitMQ (`rabbitmq:4-alpine`) and the WireMock payment gateway, apply the Liquibase changesets, and run the real service and repository against them. No in-memory database is used.

To run a specific module's test suite:
```bash
./mvnw test -pl middleware
```

---

## 4. API Endpoints

| Method | Path | Description | Idempotency Required |
|--------|------|-------------|-----------------------|
| `POST` | `/api/v1/wallets` | Create a wallet of a given `type` (required, no default) | No |
| `GET`  | `/api/v1/wallets/{phone}` | Retrieve wallet details (`wallet_id`, `user_id`, `phone`, `currency`, `type`, `created_at`) | No |
| `GET`  | `/api/v1/wallets/{phone}/balance` | Retrieve the wallet balance and when it last changed (`updated_at`) | No |
| `POST` | `/api/v1/wallets/add-money` | Request to add money to a wallet (`phone` in body). Answers 202; a worker credits the wallet after the card gateway confirms the payment | **Yes** (`Idempotency-Key`) |
| `GET`  | `/api/v1/wallets/add-money/{request_id}` | Status of an add-money request (`PENDING`, `COMPLETED`, `FAILED`) | No |
| `POST` | `/api/v1/transfers` | P2P transfer between two wallets | **Yes** (`Idempotency-Key`) |
| `GET`  | `/api/v1/wallets/{phone}/transactions` | Query paginated transaction history for a wallet | No |
| `GET`  | `/api/v1/transactions/{transaction_id}` | Retrieve details of a specific transaction | No |

### Add money

```http
POST /api/v1/wallets/add-money
Idempotency-Key: <unique string, max 255 chars>

{ "phone": "01712345678", "amount": 50000,
  "gateway_ref": "3f1c9a52-7d4e-4b8a-9c21-5e6f7a8b9c0d", "payment_method": "VISA" }
```

`gateway_ref` is the gateway's payment id, a UUID. `payment_method` is `VISA` or `MASTERCARD`. Add-money is **asynchronous**: the request is validated and queued, and the answer is `202 Accepted` with a `Location` header and the request in status `PENDING`:

```json
{ "request_id": "…", "status": "PENDING", "wallet_id": "…", "phone": "01712345678", "amount": 50000,
  "gateway_ref": "3f1c9a52-7d4e-4b8a-9c21-5e6f7a8b9c0d", "payment_method": "VISA", "attempts": 0,
  "transaction_id": null, "balance": null, "error_code": null, "error_message": null,
  "created_at": "…", "updated_at": "…" }
```

Poll `GET /api/v1/wallets/add-money/{request_id}` until `status` is final. `COMPLETED` carries the `transaction_id` and the wallet `balance` right after the credit; `FAILED` carries `error_code` and `error_message`. A replay of the same `Idempotency-Key` and body returns the same request (with its current status), so it is safe to retry the POST itself.

How it works:

1. In one database transaction the API claims the `Idempotency-Key`, checks the amount limits, the wallet and the `gateway_ref`, and inserts a `PENDING` row into `add_money_requests`. That row is both the status record and the outbox.
2. A dispatcher (every 500 ms) publishes each unpublished `PENDING` request to the RabbitMQ queue `wallet.add-money` (persistent message, publisher confirm) and marks it published. Because the message is sent from the database row, a crash between "accepted" and "queued" cannot lose a request. A `PENDING` request whose message is older than 10 minutes is published again as a safety net.
3. A worker asks that network's gateway (`POST /{visa|mastercard}/v1/payments/verify`, authenticated with the network's API key) whether the payment was captured for exactly this amount, currency and network, and for this wallet: the request carries the wallet's phone as `merchant_reference` and the gateway must echo it back. The credit is one `ADD_MONEY` transaction with two entries (`DEBIT` the gateway clearing asset account, `CREDIT` the customer wallet), and the request becomes `COMPLETED`. Topping up a system account is `422 SYSTEM_WALLET_NOT_ALLOWED`.
4. The `gateway_ref` credits only once, however many messages or requests there are: a request is processed under its row lock, a request that is no longer `PENDING` is skipped, and `transactions.gateway_ref` is unique. A `gateway_ref` that is already credited or already queued is `409 DUPLICATE_GATEWAY_REF` at once; a `FAILED` request frees its `gateway_ref`.

**Retries.** Only transient failures are retried: the gateway being unreachable, timing out or failing (`PAYMENT_GATEWAY_ERROR`), a payment the gateway has not captured yet, such as 3-D Secure in progress (`PAYMENT_PENDING`), and a payment the gateway does not know yet (`PAYMENT_NOT_FOUND`: the customer may have just paid, so a reference that never appears only fails after the last attempt). The failed attempt is counted and the request stays `PENDING` (with the last error visible) while it waits on a retry queue `wallet.add-money.retry.N`. That queue has the delay as its TTL and dead-letters back to the work queue, so the broker brings the message back without any consumer or sleeping thread, and the wait survives restarts. The delays are `miniwallet.messaging.add-money.retry-delays` (default `5s,30s,2m,10m,30m`: up to 6 attempts over about 43 minutes). When the attempts run out the request is `FAILED` with the last error code, `PAYMENT_GATEWAY_ERROR`, `PAYMENT_PENDING` or `PAYMENT_NOT_FOUND` (message ending in `gave up after N attempts`) and its id goes to the dead-letter queue `wallet.add-money.dlq`. A business rejection (declined, mismatch, ...) is final at once and never retried. An unexpected worker error (a bug, a database error) is counted and retried the same way and ends `FAILED` with `INTERNAL_SERVER_ERROR` after the last attempt, so a request that always crashes the worker cannot cycle forever. If even that cannot be recorded (the database is down), the message is dead-lettered and the dispatcher republishes the still-`PENDING` request later. The worker calls the gateway with no database transaction open and locks the request row only to write the outcome; a message that comes back before the backoff of the last recorded attempt has elapsed (a redelivery after a crash) is parked again without counting an attempt. A database lock timeout in the worker (contention on the revenue/clearing rows) is parked the same way: it never uses up an attempt, so contention alone cannot fail a paid request. The application connections set `lock_timeout=10s` and `idle_in_transaction_session_timeout=60s` (`spring.datasource.hikari.data-source-properties.options`); Liquibase uses its own connection without them.

| Error | Status | When |
|---|---|---|
| `MISSING_IDEMPOTENCY_KEY` | 400 | No `Idempotency-Key` header |
| `VALIDATION_ERROR` / `INVALID_PAYLOAD` | 400 | Missing or invalid `phone`, missing or non-integer `amount`, `gateway_ref` not a UUID, unknown `payment_method`. The message names the field, e.g. `Invalid value for field: gateway_ref` |
| `INVALID_AMOUNT` | 400 | `amount` below the minimum or above the maximum in the `limits` table (default 100–2,500,000 poisha). Checked when the request is accepted, stored and replayed like other deterministic failures |
| `WALLET_NOT_FOUND` | 404 | No wallet with that phone |
| `DUPLICATE_GATEWAY_REF` | 409 | The `gateway_ref` already credited a wallet or belongs to a request still waiting to be processed |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different request |
| `SYSTEM_WALLET_NOT_ALLOWED` | 422 | The wallet is a system ledger account |
| `ADD_MONEY_REQUEST_NOT_FOUND` | 404 | `GET` of a `request_id` that does not exist |
| `SERVICE_BUSY` | 503 | A row lock was not granted within the database `lock_timeout` (10 s). Nothing was written; retry with the same key. Has `Retry-After` |

Failures that need the gateway are not HTTP errors of the POST any more; they end the request as `FAILED` with this `error_code`:

| `error_code` | When |
|---|---|
| `PAYMENT_DECLINED` | Gateway reports the payment as declined |
| `PAYMENT_PENDING` | Gateway still reports the payment as not captured (for example 3-D Secure in progress) on every attempt |
| `PAYMENT_NOT_FOUND` / `PAYMENT_MISMATCH` | Gateway doesn't know the payment / captured a different amount, currency, network or wallet |
| `PAYMENT_GATEWAY_ERROR` | Gateway unreachable, timed out or failed on every attempt |

### Transfers

```http
POST /api/v1/transfers
Idempotency-Key: <unique string, max 255 chars>

{ "from_phone": "01712345678", "to_phone": "01812345678", "amount": 10000, "note": "Lunch" }
```

Response 201: `{ "transfer_id", "from_wallet_id", "from_phone", "to_wallet_id", "to_phone", "amount", "fee", "note", "status": "COMPLETED", "created_at" }`. Every transfer costs the sender a flat fee on top of `amount` (500 poisha / 5 BDT, stored in the `fees` table and seeded by Liquibase; changing the row affects new transfers only, and a replay returns the fee actually charged), so the sender needs `amount + fee`. Four ledger legs are written in one database transaction under one `TRANSFER` transaction whose id is the `transfer_id`: `TRANSFER_OUT` + `FEE_OUT` on the sender, `TRANSFER_IN` on the receiver and `FEE_IN` on the revenue account (seeded by Liquibase, phone `01000000001`). Only customer wallets can send or receive: a system account as sender or receiver is `422 SYSTEM_WALLET_NOT_ALLOWED`; wallets are locked in ascending id order, so opposite concurrent transfers cannot deadlock. The optional `note` (1–255 chars) is stored on the `TRANSFER` transaction, returned in the transfer response and in `GET /api/v1/transactions/{transaction_id}`, and is part of the idempotency fingerprint.

| Error | Status | When |
|---|---|---|
| `MISSING_IDEMPOTENCY_KEY` / `VALIDATION_ERROR` / `INVALID_PAYLOAD` | 400 | Missing header, missing or non-integer amount, phones missing or invalid |
| `INVALID_AMOUNT` | 400 | `amount` below the minimum, or `amount` above the maximum in the `limits` table (default 2,500,000 poisha); the fee is not part of the limit |
| `WALLET_NOT_FOUND` | 404 | Sender or receiver does not exist |
| `INSUFFICIENT_BALANCE` / `SAME_WALLET_TRANSFER` / `IDEMPOTENCY_KEY_REUSED` | 422 | Sender balance too low / same wallet on both sides / same key, different request |
| `SERVICE_BUSY` | 503 | A row lock was not granted within the database `lock_timeout` (10 s); nothing was written, retry with the same key |

### Transaction history

`GET /api/v1/wallets/{phone}/transactions` takes `type` (`ADD_MONEY`, `GATEWAY_CLEARING`, `TRANSFER_OUT`, `TRANSFER_IN`, `FEE_OUT`, `FEE_IN`), `from` / `to` (ISO-8601 instants, inclusive), `page` (from 1) and `page_size` (default 20, max 100). Results are ledger entries, newest first: one wallet's list follows its ledger chain (`entry_no`), so `balance_after` always reads on from the entry below it. `created_at` is when the entry was posted (taken once the wallet locks are held, so for an add-money after the gateway answered), not when the request started; `from` / `to` and the balance's `updated_at` use the same time. Entries carry `entry_id`, `transaction_id`, `wallet_id`, `phone`, `type`, `direction` (`DEBIT`/`CREDIT`), `amount`, `balance_after`, `counterparty_wallet_id`, `counterparty_phone` and `created_at`, plus `total_items` / `total_pages`. `GET /api/v1/transactions/{transaction_id}` returns the transaction (`type` `ADD_MONEY` or `TRANSFER`, `amount`, `gateway_ref` and `payment_method` for add-money) with all of its `entries` (`404 TRANSACTION_NOT_FOUND`). `from` after `to` is `400 INVALID_DATE_RANGE`; an unknown wallet is `404 WALLET_NOT_FOUND`.

### Mock payment gateway (WireMock)

`docker compose up` also starts `payment-gateway` (WireMock, `localhost:8089`), loaded from `docker/wiremock`. The integration tests use the same stubs. The outcome is chosen by the first group of the `gateway_ref` UUID. The reserved prefixes are named after the resulting HTTP status:

| `gateway_ref` starts with | Gateway answer | API result |
|---|---|---|
| *(any other UUID)* | Captured for the requested amount | `COMPLETED` |
| `00000402-` | Declined: `INSUFFICIENT_FUNDS` | `FAILED` `PAYMENT_DECLINED` |
| `00000202-` | Pending 3-D Secure on every call | retried, then `FAILED` `PAYMENT_PENDING` |
| `00000203-` | Pending 3-D Secure on the first two calls, then captured (one WireMock scenario shared by all such refs, reset with `POST /__admin/scenarios/reset`) | retried, then `COMPLETED` on the third attempt |
| `00000404-` | 404 | `FAILED` `PAYMENT_NOT_FOUND` |
| `00000422-` | Captured for 100 poisha | `FAILED` `PAYMENT_MISMATCH` |
| `00000423-` | Captured for another wallet (`merchant_reference` differs) | `FAILED` `PAYMENT_MISMATCH` |
| `00000500-` | 500 on every call | retried, then `FAILED` `PAYMENT_GATEWAY_ERROR` |
| `00000503-` | 503 on the first two calls, then captured (the call counter is one WireMock scenario shared by all such refs: reset it with `POST /__admin/scenarios/reset`) | retried, then `COMPLETED` on the third attempt |
| `00000504-` | Answers after 10 s (app read timeout: 5 s) | retried, then `FAILED` `PAYMENT_GATEWAY_ERROR` |

For example, `00000402-7d4e-4b8a-9c21-5e6f7a8b9c0d` simulates a declined card. A random UUID starts with one of these prefixes with probability about 1 in 4 billion.

Requests with a missing or wrong `X-Api-Key` (`visa-sandbox-key` / `mastercard-sandbox-key`) get 401.

---

## 5. Architectural Assumptions & Financial Invariants

1. **Wallet type is explicit**: `POST /api/v1/wallets` takes `user_id`, `phone` and a required `type` (`ASSET`, `EXPENSE`, `REVENUE`, `EQUITY` or `LIABILITY`); there is no default, and a missing or unknown `type` is a `400`. Any user may create a wallet of any type for now (role-based permissions will come later). The only constraint is the phone: a valid Bangladeshi mobile number (any accepted format), and **one wallet per phone** (`409 DUPLICATE_PHONE` otherwise), the same for every type. `user_id` is free: no id is reserved, and the same `user_id` may own any number of wallets of any type, customer wallets included. A wallet of another type is not used by any flow: add-money and transfers accept customer wallets only (`422 SYSTEM_WALLET_NOT_ALLOWED`).
2. **Phone number is the external identity**: every API identifies a wallet by its phone number (any accepted format, normalized to `01XXXXXXXXX`), which is unique per wallet and mandatory, system accounts included (the seeded ones use `010…` numbers). There is no operator-prefix restriction: any number of the form `01XXXXXXXXX` is valid, and uniqueness alone stops a customer taking a system account's number (`409 DUPLICATE_PHONE`). `wallet_id` (a UUIDv7) is what the ledger uses and is shown in responses next to the phone, but requests never take it. `transaction_id` is a UUIDv7 generated by the service. The client supplies `user_id` and `gateway_ref` as UUIDs, which the problem statement's examples (`u_1001`, `GW-20261105-9981`) do not. Non-UUID values are rejected with 400.
3. **Monetary Precision**: All monetary values are strictly represented in integer **poisha** (1 BDT = 100 poisha). Floating-point types are strictly forbidden to eliminate rounding errors.
4. **Transaction Bounds**: The minimum amount and the single-transaction maximum are read from the `limits` table (one row per transaction type, seeded by Liquibase with 100 poisha / 1 BDT and 2,500,000 poisha / 25,000 BDT). The maximum applies to the `amount` of a transfer; the fee is charged on top and is not counted against it. Change a row to change the limit; the database itself only requires amounts to be positive.
5. **Non-Negative Balances**: A wallet balance can never drop below zero.
   * **Double-entry ledger as source of truth**: wallets store no balance. Every money movement is a row in `transactions` (type, amount, and `gateway_ref` / `payment_method` for add-money) plus its immutable debit/credit legs in `ledger_entries` (each pointing to its transaction by `transaction_id`), and a wallet's balance is the `balance_after` of its latest leg (0 for a new wallet). Each wallet is an account of one class: `ASSET`, `EXPENSE`, `REVENUE`, `EQUITY` or `LIABILITY`. Customer wallets are `LIABILITY` (the platform owes the customer that money); Liquibase seeds two system accounts, both seeded under the owner `user_id` `00000000-0000-7000-8000-000000000002` (plain data, not reserved) and each with a seeded phone in the `010` range: `REVENUE` transfer fees (`01000000001`) and `ASSET` gateway clearing (`01000000003`). A customer registering one of these phones gets `409 DUPLICATE_PHONE`. Every account starts at balance 0 (no seeded transactions). `EXPENSE` and `EQUITY` wallets are not seeded; create them with `POST /wallets` if needed (no flow posts to them yet). ASSET and EXPENSE accounts grow on a debit, the other three on a credit.
   * **Transactions**: add-money = `DEBIT` gateway clearing + `CREDIT` customer (`GATEWAY_CLEARING` + `ADD_MONEY` legs); transfer = `DEBIT` sender (`TRANSFER_OUT` amount, `FEE_OUT` fee), `CREDIT` receiver (`TRANSFER_IN`), `CREDIT` revenue (`FEE_IN`). Customer balances plus revenue always equal the clearing asset, so the trial balance (debit-normal balances = credit-normal balances) holds. System accounts cannot send, receive or be topped up (`422 SYSTEM_WALLET_NOT_ALLOWED`).
   * **Enforced by PostgreSQL**: a deferred constraint trigger rejects, at commit, any transaction with fewer than two entries or whose debits differ from its credits; `gateway_ref` is unique, so a gateway payment credits once; transactions and entries are append-only (UPDATE/DELETE are rejected); and constraints guarantee each wallet's chain starts at 0, continues exactly from the previous balance, never forks or skips, never goes negative, uses the arithmetic of its account class, and that each leg type posts on its fixed side and account class.
6. **Deadlock Prevention**: All multi-wallet operations lock rows in ascending order of wallet IDs (`LedgerPoster.lockAndLoadChains`), preventing circular wait conditions and deadlocks.
7. **Idempotency Enforcement**:
   * Requests bearing the same `Idempotency-Key` and payload return the cached response without duplicate execution.
   * Requests reusing an `Idempotency-Key` with an altered payload return `422 Unprocessable Entity` (`IDEMPOTENCY_KEY_REUSED`).
   * Missing the `Idempotency-Key` header on required endpoints returns `400 Bad Request`.
   * Claiming the key, doing the work and storing the outcome happen in one database transaction. A concurrent request with the same key waits for it and then replays the stored outcome, so there is exactly one execution.
   * Deterministic failures are stored and replayed. For transfers that is every business rejection. For add-money it is the rejections found when the request is accepted (amount, wallet, duplicate `gateway_ref`); the idempotency key then points at the accepted request, whose own status (`COMPLETED` or `FAILED`) is what a replay shows, so a request that failed after retrying is not executed again under the same key.
   * Transfers are still synchronous, and a transient failure is not stored there, so the client can retry with the same key.
   * A key identifies one attempt, so a stored rejection is final for that key. A sender who gets `INSUFFICIENT_BALANCE` (or `WALLET_NOT_FOUND`) and then tops up must retry with a **new** `Idempotency-Key`; the old key keeps replaying the rejection.

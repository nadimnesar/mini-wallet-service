# Mini Wallet Service

A high-concurrency, ACID-compliant digital wallet backend service modeled after **Pathao Pay**. The service manages digital wallets, wallet transactions, and peer-to-peer (P2P) money transfers with strict money invariants, ascending-order locking to prevent deadlocks, and idempotency guarantees.

---

## 1. Architecture Overview

The project is structured as a Maven multi-module architecture with compiler-enforced layering:

* **`common`**: Shared utilities, error codes, and base domain exceptions.
* **`models`**: Pure domain models, value objects, and API Data Transfer Objects (DTOs).
* **`repository`**: Everything about our own database: repository interfaces, their JDBC implementations, the read/write `DataSourceConfig`, and the Liquibase schema.
* **`clients`**: Clients for external systems. Currently the Visa/Mastercard payment gateway.
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

#### Database topology (read/write split)

`docker compose up` starts two PostgreSQL nodes from the free `bitnami/postgresql:latest` image (currently PostgreSQL 18) with streaming replication, configured purely through environment variables:

| Service | Role | Host port | Notes |
|---------|------|-----------|-------|
| `postgres-primary` | Write node | `5432` | Accepts reads and writes (`POSTGRESQL_REPLICATION_MODE=master`) |
| `postgres-replica` | Read node | `5433` | Hot standby (`POSTGRESQL_REPLICATION_MODE=slave`); rejects writes |

The `repository` module (`DataSourceConfig`) exposes one `DataSource` that routes on the transaction's read-only flag:
`@Transactional(readOnly = true)` goes to the replica, and everything else goes to the primary. Replication is asynchronous, so reads that must see a write that just happened (for example, a balance check before a debit) must run in a read-write transaction.
Connection settings are under `miniwallet.datasource.write.*` / `miniwallet.datasource.read.*` in `application.properties`. You can override them with `DB_WRITE_URL`, `DB_READ_URL`, `DB_USERNAME` and `DB_PASSWORD`.
If you recreate the primary's data, also reset the replica: `docker compose down -v`. Do the same when `latest` moves to a new PostgreSQL major version, because the old data files can't be opened by the new version.

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

Docker must be running. The integration tests (`WalletServiceIntegrationTest` in `middleware`) use Testcontainers to start a real PostgreSQL (`postgres:18-alpine`), apply the Liquibase changesets, and run the real service and repository against it. No in-memory database is used.

To run a specific module's test suite:
```bash
./mvnw test -pl middleware
```

---

## 4. API Endpoints

| Method | Path | Description | Idempotency Required |
|--------|------|-------------|-----------------------|
| `POST` | `/api/v1/wallets` | Create a new user wallet | No |
| `GET`  | `/api/v1/wallets/{wallet_id}` | Retrieve wallet details and balance | No |
| `POST` | `/api/v1/wallets/{wallet_id}/add-money` | Add money to a wallet after the card gateway confirms the payment | **Yes** (`Idempotency-Key`) |
| `POST` | `/api/v1/transfers` | P2P transfer between two wallets | **Yes** (`Idempotency-Key`) |
| `GET`  | `/api/v1/transactions` | Query transactions across/by wallet (`?wallet_id=...`) | No |
| `GET`  | `/api/v1/wallets/{wallet_id}/transactions` | Query paginated transaction history for a wallet | No |
| `GET`  | `/api/v1/transactions/{transaction_id}` | Retrieve details of a specific transaction | No |

### Add money

```http
POST /api/v1/wallets/{wallet_id}/add-money
Idempotency-Key: <unique string, max 255 chars>

{ "amount": 50000, "gateway_ref": "GW-20261105-9981", "payment_method": "VISA" }
```

`payment_method` is `VISA` or `MASTERCARD`. Before crediting, the service asks that network's gateway (`POST /{visa|mastercard}/v1/payments/verify`, authenticated with the network's API key) whether the payment was captured for exactly this amount, currency and network. The response (201) is built from the ledger entry:

```json
{ "transaction_id": "…", "wallet_id": "…", "added_amount": 50000, "balance": 50000,
  "gateway_ref": "GW-20261105-9981", "payment_method": "VISA", "created_at": "…" }
```

| Error | Status | When |
|---|---|---|
| `MISSING_IDEMPOTENCY_KEY` | 400 | No `Idempotency-Key` header |
| `VALIDATION_ERROR` / `INVALID_PAYLOAD` | 400 | Amount outside 100–2,500,000 poisha, bad `gateway_ref`, unknown `payment_method` |
| `PAYMENT_DECLINED` | 402 | Gateway reports the payment as declined or not yet captured |
| `WALLET_NOT_FOUND` | 404 | Unknown wallet |
| `DUPLICATE_GATEWAY_REF` | 409 | The `gateway_ref` already credited a wallet |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different request |
| `PAYMENT_NOT_FOUND` / `PAYMENT_MISMATCH` | 422 | Gateway doesn't know the payment / captured a different amount, currency or network |
| `PAYMENT_GATEWAY_ERROR` | 502 | Gateway unreachable, timed out or failed. Not stored, so retry with the same key |

### Mock payment gateway (WireMock)

`docker compose up` also starts `payment-gateway` (WireMock, `localhost:8089`), loaded from `docker/wiremock`. The integration tests use the same stubs. The outcome is chosen by a keyword in `gateway_ref`:

| `gateway_ref` contains | Gateway answer | API result |
|---|---|---|
| *(none of the below)* | Captured for the requested amount | 201 |
| `DECLINED` | Declined: `INSUFFICIENT_FUNDS` | 402 `PAYMENT_DECLINED` |
| `PENDING` | Pending 3-D Secure | 402 `PAYMENT_DECLINED` |
| `NOTFOUND` | 404 | 422 `PAYMENT_NOT_FOUND` |
| `MISMATCH` | Captured for 100 poisha | 422 `PAYMENT_MISMATCH` |
| `ERROR` | 500 | 502 `PAYMENT_GATEWAY_ERROR` |
| `TIMEOUT` | Answers after 10 s (app read timeout: 5 s) | 502 `PAYMENT_GATEWAY_ERROR` |

Requests with a missing or wrong `X-Api-Key` (`visa-sandbox-key` / `mastercard-sandbox-key`) get 401.

---

## 5. Architectural Assumptions & Financial Invariants

1. **Monetary Precision**: All monetary values are strictly represented in integer **poisha** ($1\text{ BDT} = 100\text{ poisha}$). Floating-point types are strictly forbidden to eliminate rounding errors.
2. **Transaction Bounds**: The minimum amount for add-money and transfer operations is $100\text{ poisha}$ ($1\text{ BDT}$); the maximum single transaction limit is $2,500,000\text{ poisha}$ ($25,000\text{ BDT}$).
3. **Non-Negative Balances**: A wallet balance can never drop below zero.
   * **Ledger as source of truth**: wallets store no balance. Every money movement is an immutable entry in `wallet_transactions`, and a wallet's balance is the `balance_after` of its latest entry (0 for a new wallet). PostgreSQL constraints guarantee that each wallet's chain starts at 0, continues exactly from the previous balance, never forks or skips, never goes negative, and keeps the arithmetic consistent.
4. **Deadlock Prevention**: All multi-wallet operations lock rows in ascending order of wallet IDs (`lockInOrder`), preventing circular wait conditions and deadlocks.
5. **Idempotency Enforcement**:
   * Requests bearing the same `Idempotency-Key` and payload return the cached response without duplicate execution.
   * Requests reusing an `Idempotency-Key` with an altered payload return `422 Unprocessable Entity` (`IDEMPOTENCY_KEY_REUSED`).
   * Missing the `Idempotency-Key` header on required endpoints returns `400 Bad Request`.
   * Claiming the key, doing the work and storing the outcome happen in one database transaction. A concurrent request with the same key waits for it and then replays the stored outcome, so there is exactly one execution.
   * Deterministic failures (declined, not found, mismatch, duplicate `gateway_ref`) are stored and replayed. Transient gateway failures are not, so the client can retry with the same key.

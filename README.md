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

#### Database topology (read/write split)

`docker compose up` starts two PostgreSQL nodes from the free `bitnami/postgresql:latest` image (currently PostgreSQL 18) with streaming replication, configured purely through environment variables:

| Service | Role | Host port | Notes |
|---------|------|-----------|-------|
| `postgres-primary` | Write node | `5432` | Accepts reads and writes (`POSTGRESQL_REPLICATION_MODE=master`) |
| `postgres-replica` | Read node | `5433` | Hot standby (`POSTGRESQL_REPLICATION_MODE=slave`); rejects writes |

The `clients` module (`DataSourceConfig`) exposes one `DataSource` that routes on the transaction's read-only flag:
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
| `GET`  | `/api/v1/wallets/{phone}` | Retrieve wallet details (`wallet_id`, `user_id`, `phone`, `currency`, `type`, `created_at`) | No |
| `GET`  | `/api/v1/wallets/{phone}/balance` | Retrieve the wallet balance and when it last changed (`updated_at`) | No |
| `POST` | `/api/v1/wallets/add-money` | Add money to a wallet (`phone` in body) after the card gateway confirms the payment | **Yes** (`Idempotency-Key`) |
| `POST` | `/api/v1/transfers` | P2P transfer between two wallets | **Yes** (`Idempotency-Key`) |
| `GET`  | `/api/v1/transactions` | Query transactions across/by wallet (`?phone=...`) | No |
| `GET`  | `/api/v1/wallets/{phone}/transactions` | Query paginated transaction history for a wallet | No |
| `GET`  | `/api/v1/transactions/{transaction_id}` | Retrieve details of a specific transaction | No |

### Add money

```http
POST /api/v1/wallets/add-money
Idempotency-Key: <unique string, max 255 chars>

{ "phone": "01712345678", "amount": 50000,
  "gateway_ref": "3f1c9a52-7d4e-4b8a-9c21-5e6f7a8b9c0d", "payment_method": "VISA" }
```

`gateway_ref` is the gateway's payment id, a UUID. `payment_method` is `VISA` or `MASTERCARD`. Before crediting, the service asks that network's gateway (`POST /{visa|mastercard}/v1/payments/verify`, authenticated with the network's API key) whether the payment was captured for exactly this amount, currency and network, and for this wallet: the request carries the wallet's phone as `merchant_reference` and the gateway must echo it back. The credit is one `ADD_MONEY` transaction with two entries (`DEBIT` the gateway clearing asset account, `CREDIT` the customer wallet; topping up a system account is `422 SYSTEM_WALLET_NOT_ALLOWED`). The response (201) is built from the customer's ledger entry:

```json
{ "transaction_id": "…", "wallet_id": "…", "phone": "01712345678", "added_amount": 50000, "balance": 50000,
  "gateway_ref": "3f1c9a52-7d4e-4b8a-9c21-5e6f7a8b9c0d", "payment_method": "VISA", "created_at": "…" }
```

| Error | Status | When |
|---|---|---|
| `MISSING_IDEMPOTENCY_KEY` | 400 | No `Idempotency-Key` header |
| `VALIDATION_ERROR` / `INVALID_PAYLOAD` | 400 | Missing or invalid `phone`, amount outside 100–2,500,000 poisha, `gateway_ref` not a UUID, unknown `payment_method`. The message names the field, e.g. `Invalid value for field: gateway_ref` |
| `PAYMENT_DECLINED` | 402 | Gateway reports the payment as declined or not yet captured |
| `WALLET_NOT_FOUND` | 404 | No wallet with that phone |
| `DUPLICATE_GATEWAY_REF` | 409 | The `gateway_ref` already credited a wallet |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different request |
| `PAYMENT_NOT_FOUND` / `PAYMENT_MISMATCH` | 422 | Gateway doesn't know the payment / captured a different amount, currency, network or wallet |
| `PAYMENT_GATEWAY_ERROR` | 502 | Gateway unreachable, timed out or failed. Not stored, so retry with the same key |

### Transfers

```http
POST /api/v1/transfers
Idempotency-Key: <unique string, max 255 chars>

{ "from_phone": "01712345678", "to_phone": "01812345678", "amount": 10000, "note": "Lunch" }
```

Response 201: `{ "transfer_id", "from_wallet_id", "from_phone", "to_wallet_id", "to_phone", "amount", "fee", "note", "status": "COMPLETED", "created_at" }`. Every transfer costs the sender a flat fee of 500 poisha (5 BDT) on top of `amount`, so the sender needs `amount + fee`. Four ledger legs are written in one database transaction under one `TRANSFER` transaction whose id is the `transfer_id`: `TRANSFER_OUT` + `FEE_OUT` on the sender, `TRANSFER_IN` on the receiver and `FEE_IN` on the revenue account (seeded by Liquibase, phone `01000000001`). Only customer wallets can send or receive: a system account as sender or receiver is `422 SYSTEM_WALLET_NOT_ALLOWED`; wallets are locked in ascending id order, so opposite concurrent transfers cannot deadlock. The optional `note` (1–255 chars) is stored on the `TRANSFER` transaction, returned in the transfer response and in `GET /api/v1/transactions/{transaction_id}`, and is part of the idempotency fingerprint.

| Error | Status | When |
|---|---|---|
| `MISSING_IDEMPOTENCY_KEY` / `VALIDATION_ERROR` / `INVALID_PAYLOAD` | 400 | Missing header, amount outside 100–2,500,000 poisha, phones missing or invalid |
| `WALLET_NOT_FOUND` | 404 | Sender or receiver does not exist |
| `INSUFFICIENT_BALANCE` / `SAME_WALLET_TRANSFER` / `IDEMPOTENCY_KEY_REUSED` | 422 | Sender balance too low / same wallet on both sides / same key, different request |

### Transaction history

`GET /api/v1/wallets/{phone}/transactions` and `GET /api/v1/transactions?phone=…` (wallet optional) take `type` (`ADD_MONEY`, `GATEWAY_CLEARING`, `TRANSFER_OUT`, `TRANSFER_IN`, `FEE_OUT`, `FEE_IN`), `from` / `to` (ISO-8601 instants, inclusive), `page` (from 1) and `page_size` (default 20, max 100). Results are ledger entries, newest first, and carry `entry_id`, `transaction_id`, `wallet_id`, `phone`, `type`, `direction` (`DEBIT`/`CREDIT`), `amount`, `balance_after`, `counterparty_wallet_id`, `counterparty_phone` and `created_at`, plus `total_items` / `total_pages`. `GET /api/v1/transactions/{transaction_id}` returns the transaction (`type` `ADD_MONEY` or `TRANSFER`, `amount`, `gateway_ref` and `payment_method` for add-money) with all of its `entries` (`404 TRANSACTION_NOT_FOUND`). `from` after `to` is `400 INVALID_DATE_RANGE`; an unknown wallet is `404 WALLET_NOT_FOUND`. These reads are served by the replica, so a just-written entry can appear after the replication delay.

### Mock payment gateway (WireMock)

`docker compose up` also starts `payment-gateway` (WireMock, `localhost:8089`), loaded from `docker/wiremock`. The integration tests use the same stubs. The outcome is chosen by the first group of the `gateway_ref` UUID. The reserved prefixes are named after the resulting HTTP status:

| `gateway_ref` starts with | Gateway answer | API result |
|---|---|---|
| *(any other UUID)* | Captured for the requested amount | 201 |
| `00000402-` | Declined: `INSUFFICIENT_FUNDS` | 402 `PAYMENT_DECLINED` |
| `00000202-` | Pending 3-D Secure | 402 `PAYMENT_DECLINED` |
| `00000404-` | 404 | 422 `PAYMENT_NOT_FOUND` |
| `00000422-` | Captured for 100 poisha | 422 `PAYMENT_MISMATCH` |
| `00000423-` | Captured for another wallet (`merchant_reference` differs) | 422 `PAYMENT_MISMATCH` |
| `00000500-` | 500 | 502 `PAYMENT_GATEWAY_ERROR` |
| `00000504-` | Answers after 10 s (app read timeout: 5 s) | 502 `PAYMENT_GATEWAY_ERROR` |

For example, `00000402-7d4e-4b8a-9c21-5e6f7a8b9c0d` simulates a declined card. A random UUID starts with one of these prefixes with probability about 1 in 4 billion.

Requests with a missing or wrong `X-Api-Key` (`visa-sandbox-key` / `mastercard-sandbox-key`) get 401.

---

## 5. Architectural Assumptions & Financial Invariants

1. **Phone number is the external identity**: every API identifies a wallet by its phone number (any accepted format, normalized to `01XXXXXXXXX`), which is unique per wallet and mandatory, system accounts included (they use the unassigned `010…` prefix). `wallet_id` (a UUIDv7) is what the ledger uses and is shown in responses next to the phone, but requests never take it. `transaction_id` is a UUIDv7 generated by the service. The client supplies `user_id` and `gateway_ref` as UUIDs, which the problem statement's examples (`u_1001`, `GW-20261105-9981`) do not. Non-UUID values are rejected with 400.
1. **Monetary Precision**: All monetary values are strictly represented in integer **poisha** ($1\text{ BDT} = 100\text{ poisha}$). Floating-point types are strictly forbidden to eliminate rounding errors.
2. **Transaction Bounds**: The minimum amount for add-money and transfer operations is $100\text{ poisha}$ ($1\text{ BDT}$); the maximum single transaction limit is $2,500,000\text{ poisha}$ ($25,000\text{ BDT}$).
3. **Non-Negative Balances**: A wallet balance can never drop below zero.
   * **Double-entry ledger as source of truth**: wallets store no balance. Every money movement is a row in `transactions` (type, amount, and `gateway_ref` / `payment_method` for add-money) plus its immutable debit/credit legs in `ledger_entries` (each pointing to its transaction by `transaction_id`), and a wallet's balance is the `balance_after` of its latest leg (0 for a new wallet). Each wallet is an account of one class: `ASSET`, `EXPENSE`, `REVENUE`, `EQUITY` or `LIABILITY`. Customer wallets are `LIABILITY` (the platform owes the customer that money); Liquibase seeds four system accounts, all owned by the reserved system `user_id` `00000000-0000-7000-8000-000000000002` (no customer can use it) and each with a reserved phone in the unassigned `010` range: `REVENUE` transfer fees (`01000000001`), `ASSET` gateway clearing (`01000000003`), `EXPENSE` operating costs (`01000000005`) and `EQUITY` owner capital (`01000000006`). A customer registering one of these phones gets `409 DUPLICATE_PHONE`. Every account starts at balance 0 (no seeded transactions); no flow posts to the expense or equity accounts yet. ASSET and EXPENSE accounts grow on a debit, the other three on a credit.
   * **Transactions**: add-money = `DEBIT` gateway clearing + `CREDIT` customer (`GATEWAY_CLEARING` + `ADD_MONEY` legs); transfer = `DEBIT` sender (`TRANSFER_OUT` amount, `FEE_OUT` fee), `CREDIT` receiver (`TRANSFER_IN`), `CREDIT` revenue (`FEE_IN`). Customer balances plus revenue always equal the clearing asset, so the trial balance (debit-normal balances = credit-normal balances) holds. System accounts cannot send, receive or be topped up (`422 SYSTEM_WALLET_NOT_ALLOWED`).
   * **Enforced by PostgreSQL**: a deferred constraint trigger rejects, at commit, any transaction with fewer than two entries or whose debits differ from its credits; `gateway_ref` is unique, so a gateway payment credits once; transactions and entries are append-only (UPDATE/DELETE are rejected); and constraints guarantee each wallet's chain starts at 0, continues exactly from the previous balance, never forks or skips, never goes negative, uses the arithmetic of its account class, and that each leg type posts on its fixed side and account class.
4. **Deadlock Prevention**: All multi-wallet operations lock rows in ascending order of wallet IDs (`lockInOrder`), preventing circular wait conditions and deadlocks.
5. **Idempotency Enforcement**:
   * Requests bearing the same `Idempotency-Key` and payload return the cached response without duplicate execution.
   * Requests reusing an `Idempotency-Key` with an altered payload return `422 Unprocessable Entity` (`IDEMPOTENCY_KEY_REUSED`).
   * Missing the `Idempotency-Key` header on required endpoints returns `400 Bad Request`.
   * Claiming the key, doing the work and storing the outcome happen in one database transaction. A concurrent request with the same key waits for it and then replays the stored outcome, so there is exactly one execution.
   * Deterministic failures (declined, not found, mismatch, duplicate `gateway_ref`) are stored and replayed. Transient gateway failures are not, so the client can retry with the same key.

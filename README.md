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
| `POST` | `/api/v1/wallets/add-money` | Add money to a wallet (`wallet_id` in body) | **Yes** (`Idempotency-Key`) |
| `POST` | `/api/v1/transfers` | P2P transfer between two wallets | **Yes** (`Idempotency-Key`) |
| `GET`  | `/api/v1/transactions` | Query transactions across/by wallet (`?wallet_id=...`) | No |
| `GET`  | `/api/v1/wallets/{wallet_id}/transactions` | Query paginated transaction history for a wallet | No |
| `GET`  | `/api/v1/transactions/{transaction_id}` | Retrieve details of a specific transaction | No |

---

## 5. Architectural Assumptions & Financial Invariants

1. **Monetary Precision**: All monetary values are strictly represented in integer **poisha** ($1\text{ BDT} = 100\text{ poisha}$). Floating-point types are strictly forbidden to eliminate rounding errors.
2. **Transaction Bounds**: The minimum amount for add-money and transfer operations is $100\text{ poisha}$ ($1\text{ BDT}$); the maximum single transaction limit is $2,500,000\text{ poisha}$ ($25,000\text{ BDT}$).
3. **Non-Negative Balances**: A wallet balance can never drop below zero.
4. **Deadlock Prevention**: All multi-wallet operations lock rows in ascending order of wallet IDs (`lockInOrder`), preventing circular wait conditions and deadlocks.
5. **Idempotency Enforcement**:
   * Requests bearing the same `Idempotency-Key` and payload return the cached response without duplicate execution.
   * Requests reusing an `Idempotency-Key` with an altered payload return `422 Unprocessable Entity` (`IDEMPOTENCY_KEY_REUSED`).
   * Missing the `Idempotency-Key` header on required endpoints returns `400 Bad Request`.

# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project at a glance

- Maven multi-module Spring Boot 4 / Java 25 service: `common`, `models`, `repository`, `clients`, `services`, `middleware` (the only runnable module).
- `docker compose up` (add `--build` after code changes) runs the whole stack: a single PostgreSQL instance (`:5432`), RabbitMQ (`:5672`, management UI `:15672`), the payment-gateway mock (`:8089`) and the app (`:8080`).
- Module roles: `clients` holds every external-system client: the database (Liquibase changelogs), the payment gateway and the RabbitMQ broker (`AddMoneyQueueClient`, queue topology in `RabbitMqConfig`). `repository` holds all repositories: interfaces plus `jdbc/` implementations.
- `common` layout: `constant/`: `AppConstants` (headers, money, phone, validation), `ErrorConstants` (nested `ErrorCode` and `ErrorMessage`), `PaymentGatewayConstants`, `MessagingConstants` (queue names, properties prefix), and `DbConstants` (datasource settings plus one nested class per table with `TABLE` and every column name). All SQL in repositories and tests builds table/column names from `DbConstants`; add new tables/columns there when the schema changes. No constants in controllers. `util/` (PhoneNumberUtils, UuidGenerator), `exception/` with the base `MiniWalletException` and one subpackage per domain (`wallet`, `payment`, `transaction`, `idempotency`). Put new exceptions in the matching subpackage.
- One PostgreSQL instance and one Spring Boot `DataSource` (`spring.datasource.*`); there is no read/write routing or replica.
- Build: `./mvnw -DskipTests package`. Tests: `./mvnw test`.
- Lombok (declared once in the root pom): `@RequiredArgsConstructor` for constructor injection, `@Slf4j` for loggers, `@Getter` for plain getters, `@NoArgsConstructor(access = AccessLevel.PRIVATE)` for constant/utility classes. Use records (not `@Data`/`@Value`) for DTOs and models. Keep a hand-written constructor only when it does more than assign fields.

## API spec (`docs/openapi.yaml`)

- `docs/openapi.yaml` is the hand-written OpenAPI 3.1 spec. Keep it minimal: paths, request/response schemas, status codes.
- **Update it in the same change** whenever an API changes: a controller endpoint, path, header, query param, request/response DTO field, validation rule, or the status mapped to an error code. Commit it with that change.

## Money model (ledger is the source of truth)

- `wallets` has **no balance column**. A wallet's balance is the `balance_after` of its latest `ledger_entries` entry (by `entry_no`), or 0 if it has none. Never add a mutable balance column.
- **The phone number is the external identity of a wallet.** Every endpoint, request/response field and error message identifies wallets by phone (normalize with `PhoneNumberUtils.normalize`; resolve with `WalletRepository.findByPhone`). `wallet_id` appears in responses next to the phone but is never accepted in requests; `LedgerEntryView` supplies the phones. Wallet details and balance are two endpoints (`GET /wallets/{phone}` and `.../balance`): `Wallet` has no balance and no `updated_at` (nothing ever changes a wallet row); `WalletBalance` comes from the latest ledger entry and its `updated_at` is that entry's time.
- `POST /wallets` takes a required `type` (no default): Any user may create any type for now (role-based permissions are planned, so there is deliberately no rule on who may create what). **The phone is the only constraint**: any valid Bangladeshi number (`PhoneNumberUtils.normalize`, DB check `^01[0-9]{9}$`, no operator-prefix restriction), and one wallet per phone (`wallets.phone` is unique; `DefaultWalletService.conflictFor` turns the skipped insert into `DuplicatePhoneException`). `user_id` is free: no id is reserved and one user may own any number of wallets of any type, so never add a unique or check constraint on it.
- The ledger is double-entry. `wallets.type` is an account class: `ASSET`, `EXPENSE`, `REVENUE`, `EQUITY`, `LIABILITY` (`WalletType`; ASSET/EXPENSE are debit-normal, the rest credit-normal, see `WalletType.apply`). Customer wallets are `LIABILITY`; Liquibase seeds one system account per other class (`REVENUE_WALLET_ID`, `GATEWAY_CLEARING_WALLET_ID`, `OPERATING_EXPENSE_WALLET_ID`, `OWNER_EQUITY_WALLET_ID` in `AppConstants`), all seeded under one owner `user_id` (00000000-0000-7000-8000-000000000002, plain data, not reserved) and each with a seeded `010…` phone (`*_WALLET_PHONE`; phones are unique, so no other wallet can take one), all at balance 0 (no seeded transactions). Every wallet has a mandatory, unique phone. Services reject system accounts as customer wallets with `SystemWalletException`.
- Two append-only tables (changelogs `002-create-transactions-table.sql`, `003-create-ledger-entries-table.sql`): `transactions` has one row per business operation (`TransactionType`: `ADD_MONEY`, `TRANSFER`; amount; `gateway_ref` + `payment_method` for add-money, `gateway_ref` unique; optional `note` for transfers), and `ledger_entries` holds its debit/credit legs. Always insert the `transactions` row first, then its entries, in one database transaction.
- `ledger_entries` holds debit/credit legs, an append-only chain per wallet. Each leg carries `direction` (fixed by its `TransactionType`) and the wallet's `wallet_type`. Legs of one transaction share a `transaction_id`, and debits must equal credits. Postgres enforces it (see `003-create-ledger-entries-table.sql`):
  - a deferred constraint trigger rejects, at commit, a transaction with fewer than two entries or unbalanced entries, and triggers reject UPDATE/DELETE on both tables
  - each leg type posts on its own side and account class
  - `entry_no` runs 1, 2, 3… with no gaps or forks
  - each entry's `balance_before` must equal the previous entry's `balance_after`
  - entry 1 starts from 0
  - `balance_after >= 0`
  - the arithmetic matches the type and the wallet's account class
  - amounts are positive (the min/max per transaction type live in the `limits` table and are enforced by the service)
  - a gateway_ref credits only once (unique on `transactions`)
- `created_at` of `transactions` and `ledger_entries` is set by the repository INSERT to `clock_timestamp()`, i.e. the posting time under the wallet locks, not the transaction start. A wallet's history sorts by `entry_no` (the true order); only the all-wallets list sorts by `created_at`. Do not rely on legs of one transaction sharing a timestamp.
- To move money, lock the involved `wallets` rows (`SELECT … FOR UPDATE`, in ascending id order), read each wallet's latest entry, and insert the next entry or entries in the same transaction. Add-money (run by the worker, see below) writes two legs (`GATEWAY_CLEARING` debit on the clearing asset + `ADD_MONEY` credit on the customer) under one `ADD_MONEY` transaction (its id is the response `transaction_id`). A transfer writes four legs (`TRANSFER_OUT` + `FEE_OUT` on the sender, `TRANSFER_IN` on the receiver, `FEE_IN` on the revenue account) under one `TRANSFER` transaction (its id is the `transfer_id`); the flat fee is read from the `fees` table (seeded by Liquibase changeset `005`, edit the row to change it; the single-transaction limit is read from the `limits` table, changeset `006`, and for a transfer it applies to `amount + fee`; a replay returns the fee of the original `FEE_OUT` leg), and the revenue and clearing accounts are locked in every transfer/add-money (a deliberate hot row). Never UPDATE or DELETE ledger rows. Tests that insert raw rows must insert the transaction and all its balanced entries in one statement (a data-modifying CTE); the test reset truncates both tables.

## Service layer layout

- `services` is packaged by role under `com.pathaopay.miniwallet.service`:
  - `service`: the public interfaces (`TransactionService`, `WalletService`); middleware and tests depend only on these;
  - `service.impl`: `DefaultTransactionService` (accept add-money, transfer, history) and `DefaultWalletService`, which only orchestrate;
  - `service.addmoney`: `AddMoneyProcessor` (the worker: verify the payment, credit, retry) and `AddMoneyDispatcher` (outbox relay);
  - `service.idempotency`: `IdempotentExecutor` (Idempotency-Key claim/replay, request fingerprint);
  - `service.ledger`: `LedgerPoster` (wallet locks in ascending id order, per-wallet `Chain` that appends legs);
  - `service.rules`: `TransactionRules` (limits, customer-wallet and duplicate-gateway_ref checks);
  - `service.mapper`: `TransactionResponses` (response building and replay rebuilders).
  Put new shared logic in the matching package, not back into `DefaultTransactionService`.
- The two history endpoints share `TransactionHistoryParams` (middleware): filters and paging with their validation. A parameter that cannot be converted (bad enum or date) is `INVALID_ARGUMENT` via `GlobalExceptionHandler`.

## Asynchronous add-money (RabbitMQ)

- `POST /wallets/add-money` only accepts: in ONE idempotent transaction (see below) it checks limits, wallet and gateway_ref and inserts a `PENDING` row into `add_money_requests` (changelog `007`), then answers `202` with `request_id`. Limits are checked only here: the worker must not re-check them, because the customer has paid by then and a limit change would fail a paid request. The idempotency `resource_id` is that request id, so a replay returns the request's current status. Status is read with `GET /wallets/add-money/{request_id}`.
- `add_money_requests` is the status record **and the outbox**: `AddMoneyDispatcher` (`@Scheduled`, in `services`) publishes unpublished `PENDING` rows to the broker with publisher confirms and sets `published_at`; never publish to RabbitMQ from inside the accepting transaction (dual write). A partial unique index on `gateway_ref` (rows not `FAILED`) makes a second open request for the same ref a 409 at acceptance.
- `AddMoneyListener` (`middleware`) consumes `wallet.add-money` and calls `TransactionService.processAddMoney`, which locks the request row, verifies the payment, credits the wallet (the former synchronous flow) and marks it `COMPLETED`/`FAILED` in one transaction. Duplicate deliveries are safe: a request that is not `PENDING` is skipped.
- Retries: a `MiniWalletException` with `isRetryable() == true` (a gateway outage, or a payment the gateway has not captured yet: `PaymentPendingException`) is counted (`attempts`), left `PENDING` and parked on `wallet.add-money.retry.N` (TTL queue that dead-letters back to the work queue; delays from `miniwallet.messaging.add-money.retry-delays`). When the attempts run out the request is `FAILED` and its id is sent to `wallet.add-money.dlq`. Non-retryable exceptions fail the request at once. Broker calls happen after the database transaction committed. Everything uses the default exchange (queue name = routing key).
- Tests: `AbstractIntegrationTest` starts RabbitMQ with two 300 ms retries, purges the queues and truncates `add_money_requests` before each test; use `topUp(...)` / `awaitFinished(...)` to fund a wallet or wait for a request. The mock gateway has a `00000503-` scenario (503, 503, then captured).

## Idempotent endpoints (add-money, transfers)

- Follow `DefaultTransactionService.addMoney` / `transfer`, which run their work through `IdempotentExecutor.execute` (the whole engine lives there; the request fingerprint is `IdempotentExecutor.fingerprint`). In ONE `TransactionTemplate` transaction it does `idempotencyRepository.tryClaim` (blocks on a concurrent claim of the same key), do the work, then `completeWithResource` / `completeWithError`. If the key is already used, replay: different request hash → `IDEMPOTENCY_KEY_REUSED`, otherwise rebuild the response from the stored resource or re-raise the stored error (`ReplayedFailureException`).
- Store only deterministic failures. Exceptions with `isRetryable() == true` (e.g. `PaymentGatewayException`) must propagate so the claim rolls back. (Add-money no longer calls the gateway inside this transaction: its retryable failures are handled by the worker, see above.)
- HTTP status is mapped from the error code in `GlobalExceptionHandler.STATUS_BY_ERROR_CODE`, so replayed errors keep their status. Add new error codes there. A failure that only the add-money worker produces (declined, mismatch, retries exhausted) is not an HTTP status of the POST: it is stored on the request (`error_code`).

## Payment gateway mock

- `PaymentGatewayClient` (clients) is implemented over HTTP by `HttpPaymentGatewayClient`, which forces HTTP/1.1 (WireMock resets h2c upgrades). Locally and in tests the gateway is WireMock with stubs in `docker/wiremock`. Scenarios are picked by a reserved prefix of the `gateway_ref` UUID, e.g. `00000402-…` for declined (see README). The verify request carries the wallet phone as `merchant_reference`, and the gateway must echo it (`00000423-…` simulates another wallet). Extend those stubs instead of mocking the client in tests.

## Database schema (Liquibase)

- Changelogs live in `clients/src/main/resources/db/changelog/`, one formatted-SQL file per table (`sql/00N-create-<table>-table.sql`).
- **No comments in changeset SQL files.** Only the Liquibase directives (`--liquibase formatted sql`, `--changeset`, `--rollback`) and SQL. Explain the schema in code or docs instead.
- **Pre-release: no backward-compatible migrations.** All data is test data. To change the schema, edit the table's existing changeset in place to show the final shape. Don't add `ALTER`/data-migration changesets.
- After editing an applied changeset, reset local data: `docker compose down -v`. Liquibase refuses to start on a checksum mismatch. Testcontainers tests always start from an empty database.

## Committing

Only commit when the user asks. Never commit to `master` without the user's go-ahead. Never force-push or rewrite pushed history.

### Message format: Conventional Commits

```
<type>(<scope>): <summary>

<optional body: what changed and why, wrapped at 72 chars>
```

- **summary**: imperative mood, lowercase first word, no trailing period, at most 72 characters (aim for 50).
  Example: `add read/write datasource routing`, not `Added routing.`
- **type** (pick one):
  | type | use for |
  |------|---------|
  | `feat` | new behaviour or capability |
  | `fix` | bug fix |
  | `refactor` | code change with no behaviour change |
  | `perf` | performance improvement |
  | `test` | adding or changing tests only |
  | `docs` | README, CLAUDE.md, docs/, comments only |
  | `build` | Maven poms, Dockerfile, dependencies |
  | `ci` | CI pipeline config |
  | `chore` | tooling, repo housekeeping, compose/infra config |
  | `revert` | reverting an earlier commit |
- **scope**: the Maven module touched (`common`, `models`, `repository`, `clients`, `services`, `middleware`), or `docker`, `readme`, `deps` or `repo`. Omit the scope only when a change truly spans everything.
- **body**: required when the *why* isn't obvious from the summary. Mention trade-offs and caveats.
- **no AI attribution**: never add `Co-Authored-By: Claude ...` (or any other AI/tool co-author) trailer, or "Generated with Claude Code" lines, to commit messages or PR descriptions. Commits are authored solely by the repository owner.
- **breaking changes**: add `!` after the scope (`feat(clients)!: ...`) and a `BREAKING CHANGE:` footer.

### Splitting work into commits

- One logical change per commit. Keep commits small and reviewable.
- Each commit should build (`./mvnw -q -DskipTests package`).
- Separate infrastructure (`chore(docker)`, `build`) from application code (`feat`/`fix`) and from docs.
- When one file holds several logical changes, stage only the relevant part of the file. Don't lump unrelated hunks together.
- Before committing, review `git status` and `git diff --cached`. Never stage secrets, `target/`, `.idea/` or local env files.

### Examples

```
feat(clients): add read/write datasource routing
chore(docker): add postgres service
build(docker): containerize app and run full stack via compose
fix(middleware): correct GlobalExceptionHandler package
docs(readme): document database topology
```

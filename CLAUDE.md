# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project at a glance

- Maven multi-module Spring Boot 4 / Java 25 service: `common`, `models`, `repository`, `clients`, `services`, `middleware` (the only runnable module).
- `docker compose up` (add `--build` after code changes) runs the whole stack: the PostgreSQL primary (write, `:5432`), the streaming read replica (`:5433`), and the app (`:8080`).
- Module roles: `clients` holds every external-system client: the database (`DataSourceConfig`, Liquibase changelogs) and the payment gateway. `repository` holds all repositories: interfaces plus `jdbc/` implementations.
- `common` layout: `constant/`: `AppConstants` (headers, money, phone, validation), `ErrorConstants` (nested `ErrorCode` and `ErrorMessage`), `PaymentGatewayConstants`, and `DbConstants` (datasource settings plus one nested class per table with `TABLE` and every column name). All SQL in repositories and tests builds table/column names from `DbConstants`; add new tables/columns there when the schema changes. No constants in controllers. `util/` (PhoneNumberUtils, UuidGenerator), `exception/` with the base `MiniWalletException` and one subpackage per domain (`wallet`, `payment`, `transaction`, `idempotency`). Put new exceptions in the matching subpackage.
- Read/write routing lives in `clients` (`DataSourceConfig`): `@Transactional(readOnly = true)` goes to the replica, and everything else goes to the primary.
- Build: `./mvnw -DskipTests package`. Tests: `./mvnw test`.
- Lombok (declared once in the root pom): `@RequiredArgsConstructor` for constructor injection, `@Slf4j` for loggers, `@Getter` for plain getters, `@NoArgsConstructor(access = AccessLevel.PRIVATE)` for constant/utility classes. Use records (not `@Data`/`@Value`) for DTOs and models. Keep a hand-written constructor only when it does more than assign fields.

## API spec (`docs/openapi.yaml`)

- `docs/openapi.yaml` is the hand-written OpenAPI 3.1 spec. Keep it minimal: paths, request/response schemas, status codes.
- **Update it in the same change** whenever an API changes: a controller endpoint, path, header, query param, request/response DTO field, validation rule, or the status mapped to an error code. Commit it with that change.

## Money model (ledger is the source of truth)

- `wallets` has **no balance column**. A wallet's balance is the `balance_after` of its latest `ledger_entries` entry (by `entry_no`), or 0 if it has none. Never add a mutable balance column.
- **The phone number is the external identity of a wallet.** Every endpoint, request/response field and error message identifies wallets by phone (normalize with `PhoneNumberUtils.normalize`; resolve with `WalletRepository.findByPhone`). `wallet_id` appears in responses next to the phone but is never accepted in requests; `LedgerEntryView` supplies the phones. Wallet details and balance are two endpoints (`GET /wallets/{phone}` and `.../balance`): `Wallet` has no balance and no `updated_at` (nothing ever changes a wallet row); `WalletBalance` comes from the latest ledger entry and its `updated_at` is that entry's time.
- The ledger is double-entry. `wallets.type` is an account class: `ASSET`, `EXPENSE`, `REVENUE`, `EQUITY`, `LIABILITY` (`WalletType`; ASSET/EXPENSE are debit-normal, the rest credit-normal, see `WalletType.apply`). Customer wallets are `LIABILITY`; Liquibase seeds one system account per other class (`REVENUE_WALLET_ID`, `GATEWAY_CLEARING_WALLET_ID`, `OPERATING_EXPENSE_WALLET_ID`, `OWNER_EQUITY_WALLET_ID` in `AppConstants`), all owned by `SYSTEM_USER_ID` and each with a reserved `010…` phone (`*_WALLET_PHONE`; customer wallets must use an assigned prefix `013`–`019`, checked by `PhoneNumberUtils.normalizeCustomer` and a DB constraint), all at balance 0 (no seeded transactions). Every wallet has a mandatory, unique phone; customer (`LIABILITY`) wallets can never use `SYSTEM_USER_ID` (a DB check). `user_id` is unique among customer wallets only. Services reject system accounts as customer wallets with `SystemWalletException`.
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
- To move money, lock the involved `wallets` rows (`SELECT … FOR UPDATE`, in ascending id order), read each wallet's latest entry, and insert the next entry or entries in the same transaction. Add-money writes two legs (`GATEWAY_CLEARING` debit on the clearing asset + `ADD_MONEY` credit on the customer) under one `ADD_MONEY` transaction (its id is the response `transaction_id`). A transfer writes four legs (`TRANSFER_OUT` + `FEE_OUT` on the sender, `TRANSFER_IN` on the receiver, `FEE_IN` on the revenue account) under one `TRANSFER` transaction (its id is the `transfer_id`); the flat fee is read from the `fees` table (seeded by Liquibase changeset `005`, edit the row to change it; the single-transaction limit is read from the `limits` table, changeset `006`, and for a transfer it applies to `amount + fee`; a replay returns the fee of the original `FEE_OUT` leg), and the revenue and clearing accounts are locked in every transfer/add-money (a deliberate hot row). Never UPDATE or DELETE ledger rows. Tests that insert raw rows must insert the transaction and all its balanced entries in one statement (a data-modifying CTE); the test reset truncates both tables.

## Idempotent endpoints (add-money, transfers)

- Follow `DefaultTransactionService.addMoney`. In ONE `TransactionTemplate` transaction: `idempotencyRepository.tryClaim` (blocks on a concurrent claim of the same key), do the work, then `completeWithResource` / `completeWithError`. If the key is already used, replay: different request hash → `IDEMPOTENCY_KEY_REUSED`, otherwise rebuild the response from the stored resource or re-raise the stored error (`ReplayedFailureException`).
- Store only deterministic failures. Exceptions with `isRetryable() == true` (e.g. `PaymentGatewayException`) must propagate so the claim rolls back.
- HTTP status is mapped from the error code in `GlobalExceptionHandler.STATUS_BY_ERROR_CODE`, so replayed errors keep their status. Add new error codes there.

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
- **body**: required when the *why* isn't obvious from the summary. Mention trade-offs and caveats (e.g. replica lag).
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
chore(docker): add postgres primary and read replica
build(docker): containerize app and run full stack via compose
fix(middleware): correct GlobalExceptionHandler package
docs(readme): document database topology
```

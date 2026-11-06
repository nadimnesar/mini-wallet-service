# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project at a glance

- Maven multi-module Spring Boot 4 / Java 25 service: `common`, `models`, `repository`, `clients`, `services`, `middleware` (the only runnable module).
- `docker compose up` (add `--build` after code changes) runs the whole stack: the PostgreSQL primary (write, `:5432`), the streaming read replica (`:5433`), and the app (`:8080`).
- Module roles: `clients` holds every external-system client: the database (`DataSourceConfig`, Liquibase changelogs) and the payment gateway. `repository` holds all repositories: interfaces plus `jdbc/` implementations.
- `common` layout: `constant/AppConstants` (all constants), `util/` (PhoneNumberUtils, UuidGenerator), `exception/` with the base `MiniWalletException` and one subpackage per domain (`wallet`, `payment`, `transaction`, `idempotency`). Put new exceptions in the matching subpackage.
- Read/write routing lives in `clients` (`DataSourceConfig`): `@Transactional(readOnly = true)` goes to the replica, and everything else goes to the primary.
- Build: `./mvnw -DskipTests package`. Tests: `./mvnw test`.

## Money model (ledger is the source of truth)

- `wallets` has **no balance column**. A wallet's balance is the `balance_after` of its latest `wallet_transactions` entry (by `entry_no`), or 0 if it has none. Never add a mutable balance column.
- `wallet_transactions` is an append-only chain per wallet. Postgres enforces it (see `002-create-wallet-transactions-table.sql`):
  - `entry_no` runs 1, 2, 3… with no gaps or forks
  - each entry's `balance_before` must equal the previous entry's `balance_after`
  - entry 1 starts from 0
  - `balance_after >= 0`
  - the arithmetic matches the type
  - amounts are 100–2,500,000 poisha
  - a gateway_ref credits only once
- To move money, lock the involved `wallets` rows (`SELECT … FOR UPDATE`, in ascending id order), read each wallet's latest entry, and insert the next entry or entries in the same transaction. A transfer writes both legs (`TRANSFER_OUT` + `TRANSFER_IN`) with the same `reference_id`. Never UPDATE or DELETE ledger rows.

## Idempotent endpoints (add-money, transfers)

- Follow `DefaultTransactionService.addMoney`. In ONE `TransactionTemplate` transaction: `idempotencyRepository.tryClaim` (blocks on a concurrent claim of the same key), do the work, then `completeWithResource` / `completeWithError`. If the key is already used, replay: different request hash → `IDEMPOTENCY_KEY_REUSED`, otherwise rebuild the response from the stored resource or re-raise the stored error (`ReplayedFailureException`).
- Store only deterministic failures. Exceptions with `isRetryable() == true` (e.g. `PaymentGatewayException`) must propagate so the claim rolls back.
- HTTP status is mapped from the error code in `GlobalExceptionHandler.STATUS_BY_ERROR_CODE`, so replayed errors keep their status. Add new error codes there.

## Payment gateway mock

- `PaymentGatewayClient` (clients) is implemented over HTTP by `HttpPaymentGatewayClient`, which forces HTTP/1.1 (WireMock resets h2c upgrades). Locally and in tests the gateway is WireMock with stubs in `docker/wiremock`. Scenarios are picked by a reserved prefix of the `gateway_ref` UUID, e.g. `00000402-…` for declined (see README). Extend those stubs instead of mocking the client in tests.

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

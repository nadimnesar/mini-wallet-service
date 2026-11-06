# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project at a glance

- Maven multi-module Spring Boot 4 / Java 25 service: `common`, `models`, `repository`, `clients`, `services`, `middleware` (the only runnable module).
- `docker compose up` (add `--build` after code changes) runs the whole stack: the PostgreSQL primary (write, `:5432`), the streaming read replica (`:5433`), and the app (`:8080`).
- Module roles: `repository` owns our database (interfaces, `jdbc/` implementations, `DataSourceConfig`, Liquibase changelogs); `clients` only talks to external systems (payment gateway).
- Read/write routing lives in `repository` (`DataSourceConfig`): `@Transactional(readOnly = true)` goes to the replica, and everything else goes to the primary.
- Build: `./mvnw -DskipTests package`. Tests: `./mvnw test`.

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

# Kotlin backend template

Reusable backend starter: Kotlin on the JVM, coroutines, Ktor, jOOQ, Flyway and
PostgreSQL, with Arrow for typed errors, an HTTP API and a polling worker. The
users feature and the sample job are examples to replace with your own.

Gradle modules follow dependency boundaries that Gradle and a dependency check
enforce. See [ARCHITECTURE.md](./ARCHITECTURE.md) for code placement, errors,
dependency injection and testing.

## Prerequisites

- **A JDK 17 or newer** to run Gradle. The build compiles with JDK 25, which
  Gradle downloads when none is installed (foojay toolchain resolver).
- **Docker** with Compose, for PostgreSQL, the PostgreSQL tests, jOOQ code
  generation and the container stack.

## Getting started

```sh
cp .env.example .env
docker compose up -d --wait postgresql
set -a; source .env; set +a
./gradlew :database:migrate
```

Then run the applications in separate terminals, with `.env` loaded the same
way:

```sh
./gradlew :api-server:run
./gradlew :worker-server:run
```

The API listens on `http://127.0.0.1:8000`; `/openapi.json` is its OpenAPI 3.1
document. `./gradlew :worker-server:run --args=once` runs one pass of every
job, then exits.

### Environment

| Variable                        | Default     | Used by                                                      |
| ------------------------------- | ----------- | ------------------------------------------------------------ |
| `DATABASE_URL`                  | required    | API, worker, migrate                                         |
| `DATABASE_STATEMENT_TIMEOUT_MS` | `10000`     | API and worker: PostgreSQL cancels a longer statement        |
| `API_HOST`                      | `127.0.0.1` | API                                                          |
| `API_PORT`                      | `8000`      | API and its healthcheck                                      |
| `WORKER_POLL_INTERVAL_MS`       | `5000`      | Worker: the pause after each pass of a job                   |
| `WORKER_PASS_TIMEOUT_MS`        | `60000`     | Worker: a pass that runs longer fails the worker             |
| `LOG_LEVEL`                     | `info`      | Everything: `trace`, `debug`, `info`, `warn`, `error`, `off` |

`DATABASE_URL` is a libpq-style URL, `postgresql://user:password@host:port/db`,
and its query parameters, such as `sslmode=require`, pass through to pgjdbc. An
empty value counts as unset. An invalid value stops the process at startup with
exit code 1 and a line naming the variable, never its value. The URL is held in
`Redacted`, whose `toString` prints `<redacted>`.

## Application behavior

| Endpoint            | Purpose                                                                          |
| ------------------- | -------------------------------------------------------------------------------- |
| `GET /health/live`  | Process liveness; never touches PostgreSQL                                       |
| `GET /health/ready` | PostgreSQL readiness (`select 1`, 5 second limit); 503 when unavailable          |
| `GET /health`       | Alias for readiness                                                              |
| `GET /users`        | Up to `limit` users (1–100, default 50), newest first; 400 for any other `limit` |
| `GET /users/{id}`   | One user; 400 for a malformed id, 404 when unknown                               |
| `POST /users`       | 201 with the user; 400 for invalid input, 409 for a taken email, 413 > 4 KiB     |
| `GET /openapi.json` | OpenAPI 3.1 document generated from the routes' `describe` blocks                |

Every error response has the same shape:

```json
{ "error": { "code": "USER_NOT_FOUND", "message": "User not found" } }
```

Invalid path parameters, query strings, malformed JSON, missing and unknown
fields, and a body that is not `application/json` are `400 INVALID_REQUEST`;
unknown routes are `404 NOT_FOUND` and unsupported methods
`405 METHOD_NOT_ALLOWED`. A failed database operation is `500 DATABASE_ERROR`,
failed readiness `503 DATABASE_UNAVAILABLE`, and a handler that throws
`500 INTERNAL_ERROR`. Responses never carry the cause.

The API starts while the database is unavailable, because the pool opens
connections on demand. Liveness stays 200 during an outage.

**Time limits.** On the API's and worker's connections PostgreSQL cancels a
statement after `DATABASE_STATEMENT_TIMEOUT_MS` (SQLSTATE 57014), a lock wait
after 8 seconds (55P03), and a session idle inside a transaction after 30
seconds. A query waits at most 5 seconds for a pooled connection. Because
PostgreSQL's limits cannot fire when the server stops answering, every
repository call also gives up by itself after the pool wait, the statement
timeout and 2 seconds more (17 seconds by default), and readiness after 5
seconds. A call that gives up, or whose caller is cancelled, **cancels its
statement**: pgjdbc sends PostgreSQL a cancel request, so the server stops the
query rather than running it to its own timeout. Migrations run on their own
connection without a statement timeout and give up after waiting 5 seconds for
a lock.

**Worker.** Each job runs on its own coroutine, waiting
`WORKER_POLL_INTERVAL_MS` after each pass. The first failure stops the worker
with exit code 1, and the supervisor (Compose's `restart: unless-stopped`)
starts it again. A pass that runs longer than `WORKER_PASS_TIMEOUT_MS` is
cancelled, along with its running statement, and fails the worker naming the
job. SIGINT or SIGTERM cancels the running pass and closes the pool; the JVM
then exits 130 or 143. The sample job reads up to five users and logs how many
at `debug`. Polling has no claims, leases or retries.

### Logs

Every process writes one JSON object per line to standard output:

```json
{"timestamp":"2026-11-10T14:06:52.579689Z","message":"Sent HTTP response","logger":"kotlintemplate.api.AccessLog","level":"INFO","trace_id":"217ec2384c20468dbfebf82e35a4b39b","method":"GET","path":"/users","status":200,"duration_ms":140,"cf_ray":"8f1d2c3b4a5e6f70-FRA"}
```

- The API logs one access line per request: Error for a 5xx, Info otherwise.
  Passing health probes are not logged. The path is logged without its query
  string; `cf_ray` only when it is a short, plain value.
- Each request and each worker pass carries its own `trace_id` in the MDC, and
  coroutines carry the MDC across threads (`MDCContext`), so every line it logs
  has it.
- A persistence failure is logged with its port, operation, reason and code
  only, such as `"error":"UserRepositoryError","operation":"list","reason":"PoolTimedOut","code":"08001"`.
- jOOQ's execute logging is off, and HikariCP and jOOQ log at Info at most
  whatever `LOG_LEVEL` says, so SQL text and the JDBC URL never reach the logs.
  pgjdbc's java.util.logging output is routed to the same JSON lines.

## Commands

| Command                                  | What it does                                                          |
| ---------------------------------------- | --------------------------------------------------------------------- |
| `scripts/check.sh`                       | Every check below, plus `verifySchema` and the PostgreSQL tests       |
| `./gradlew check`                        | Compile (warnings as errors), unit and route tests, detekt, ktlint, dependency direction |
| `./gradlew spotlessApply`                | Formats the Kotlin sources and build scripts                          |
| `./gradlew :database:postgresTest`       | Adapters against PostgreSQL in a Testcontainers container             |
| `./gradlew :database:generateSchema`     | Regenerates jOOQ's code from the migrations (Docker)                  |
| `./gradlew :database:verifySchema`       | Fails when the generated code is out of date with the migrations      |
| `./gradlew :database:migrate`            | Applies pending migrations to `DATABASE_URL`                          |
| `./gradlew :api-server:run`              | Runs the API                                                          |
| `./gradlew :worker-server:run`           | Polls the jobs; `--args=once` runs one pass of each                   |

## Containers

```sh
docker compose --profile app up -d --build
docker compose --profile tools run --rm worker-once
```

The image builds and checks the project, then copies the jars into a
distroless Java 25 image that runs as an unprivileged user. The command is the
entry point's main class. Compose runs `migrate` first, then the API
(healthchecked on liveness) and the worker.

## Compared with the Node and Rust templates

- **Kept:** the layer boundaries, typed errors in every port's signature,
  failures that hold only safe diagnostics, the error envelope, time limits on
  every database call, JSON logs with a trace id, the worker's semantics.
- **Better than Rust:** a call that gives up cancels its statement on the
  server.
- **Weaker:** Java libraries throw. The database adapter catches jOOQ's
  `DataAccessException` at its boundary, but nothing proves every throwing call
  is caught; an uncaught one becomes a 500 `INTERNAL_ERROR`.
- **JVM costs:** a few seconds to start, a few hundred MB of memory per
  process, and an image around 400 MB, most of it the JRE. The SIGTERM exit code is the JVM's,
  143, not 0.
- **Not ported:** a separate runtime database role, the VPS and Cloudflare
  Tunnel deployment, the release script and image scan, a rename script (search
  for `kotlin-template`, `kotlin_template` and `kotlintemplate`), and Swagger UI
  (point any OpenAPI viewer at `/openapi.json`).

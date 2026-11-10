# Architecture and code placement

The build is organized by **architectural boundary** at the Gradle module
level. Application code declares what it needs as interfaces (ports), adapters
implement them, and each executable chooses the implementations in its
composition root. Gradle and the `checkDependencyDirection` task enforce the
boundaries; nothing here relies on review alone.

## Dependency direction

Arrows mean "may depend on":

```text
apps/api (routes) ---------+
                           +--> application --> domain
apps/worker (jobs) --------+

modules/database ---------> application + domain + config

apps/api-server -----> api + database + config      (composition roots)
apps/worker-server --> worker + database + config
```

- `domain` depends on Arrow only: no serialization, SQL or HTTP. It owns
  business values, their invariants and domain errors.
- `application` depends on `domain`. It owns the ports (`UserRepository`,
  `DatabaseHealth`) and `PersistenceFailure`, the failure every port reports.
- `config` owns validated environment configuration and the JSON logging every
  entry point installs.
- `database` is the outbound adapter: jOOQ, HikariCP, pgjdbc, Flyway, the
  migrations, the generated schema code and the `migrate` entry point.
- `api` and `worker` are libraries of routes and jobs. They depend on
  `application` and `domain`, never on `database`.
- `api-server` and `worker-server` are the only modules that may depend on
  `database`. They read the configuration, open the pool, put the adapters
  behind the ports, and run.
- `tools/jooq-codegen` generates `database`'s jOOQ code; nothing runs it at
  runtime.

### How it is enforced

| Rule                                                        | Enforced by                                                    |
| ----------------------------------------------------------- | -------------------------------------------------------------- |
| A module compiles only against what its build file declares | Gradle; `implementation` keeps a dependency's dependencies off |
| jOOQ, pgjdbc, HikariCP, Flyway only in `database`           | `checkDependencyDirection` (in `kotlin-conventions`)           |
| `database` only in the composition roots                    | `checkDependencyDirection`                                     |
| Ktor only in `api` and `api-server`; kotlinx.serialization only in `api` | `checkDependencyDirection`                        |
| Logback only in `config`                                    | `checkDependencyDirection`                                     |
| Every error case gets an answer                             | The compiler: `when` over a sealed type must be exhaustive; detekt's `ElseCaseInsteadOfExhaustiveWhen` rejects `else` |
| An `Either` holding an error is never dropped               | The compiler's return-value checker (`-Xreturn-value-checker=full`) |
| A driver exception never reaches a log or response          | `bounded()` reduces it to `Diagnostics` where it is caught     |
| Values are valid once built                                 | Private constructors; `Email.parse`, `DisplayName.parse`       |
| No `println`; `System.getenv` only in `config`              | detekt's `ForbiddenMethodCall`                                 |
| Generated schema code matches the migrations                | `:database:verifySchema`                                       |

Add a group to the rules in `build-logic/src/main/kotlin/kotlin-conventions.gradle.kts`
when it should stay in one module.

## Layout

```text
modules/
  domain/.../User.kt                 # UserId, Email, DisplayName, User, domain errors
  application/.../
    PersistenceFailure.kt            # Operation, Diagnostics, PersistenceFailure, PersistenceReport
    DatabaseHealth.kt                # readiness port
    UserRepository.kt                # UserRepository port and its per-method errors
  config/.../
    AppConfig.kt                     # AppConfig.fromEnv, validated once at startup
    Logging.kt, resources/logback.xml  # initLogging: JSON lines at LOG_LEVEL
    Redacted.kt                      # toString prints <redacted>
  database/
    src/main/.../Postgres.kt         # Database (pool + jOOQ), PostgreSQL limits, databaseCallTimeout
    src/main/.../CallError.kt        # bounded(): every call has a time limit and cancels on giving up
    src/main/.../PgUserRepository.kt, PgDatabaseHealth.kt
    src/main/.../Migrate.kt, MigrationError.kt
    src/main/resources/db/migration/ # Flyway migrations: V<n>__<name>.sql
    src/generated/kotlin/            # jOOQ's code, generated from the migrations; never edit
    src/test/.../PostgresTest.kt     # adapters against PostgreSQL (tag "postgres")
apps/
  api/.../
    Api.kt                           # AppState, api(): plugins and every route
    ApiError.kt                      # the error envelope: one enum entry per code
    Requests.kt                      # JSON bodies, path and query parameters: 400 or 413
    AccessLog.kt                     # one line per request, trace_id in the MDC
    OpenApi.kt                       # /openapi.json from the routes' describe blocks
    Health.kt, Users.kt              # route groups: DTOs, handlers, error mapping
  api-server/.../Main.kt             # composition root; Healthcheck.kt for the image
  worker/.../
    JobRunner.kt                     # WorkerDeps, Job, JobError, pollJobs, runJobsOnce
    Jobs.kt                          # the job list and each job
  worker-server/.../Main.kt          # composition root: `worker` or `worker once`
tools/jooq-codegen/                  # PostgreSQL container + Flyway + jOOQ's generator
build-logic/                         # kotlin-conventions: toolchain, lint, tests, dependency rules
```

## Adding a feature

1. **Domain**: values with a private constructor and a `parse` returning
   `Either<InvalidX, X>`, the entity, and domain errors as data classes.
2. **Application**: a port interface of `suspend` functions returning
   `Either`, an `Operation` enum naming its calls, and one sealed interface
   per method that can fail in more than one way (`FindUserError`). A method
   with only persistence failures returns `PersistenceFailure<YourOperation>`.
3. **Database**: a migration in `db/migration`, then
   `./gradlew :database:generateSchema` and commit `src/generated`. In the
   adapter, run every query through `bounded(database.callLimit, query) { … }`,
   map a known constraint to its domain error, and everything else with
   `toFailure(operation)`. Records pass the domain's `parse` again; invalid
   stored data is a persistence failure, never a domain error.
4. **API**: a `Route.yourRoutes(port)` function taking only its ports,
   `@Serializable` DTOs, a `toApiError()` that names every case with `when`,
   new `ApiError` entries if needed, and a `describe { }` block on each route.
5. **Composition**: add the port to `AppState` or `WorkerDeps`, and its adapter
   in `api-server` or `worker-server`.

## Dependency injection

Ports are interfaces; adapters are passed to constructors and functions. No
container: `AppState` holds every port the routes use, each route group takes
only its own as parameters, and a test serves one group with just the fakes it
needs. Composition roots open `Database` once and pass it to each adapter.
Closing the pool runs explicitly after the server or worker stops, in the
shutdown hook or after the worker's work returns.

## Errors

- Errors are values. Ports return `Either<Error, Value>`; the error type is a
  sealed interface listing every case, so callers handle each one and the
  compiler checks they did. Arrow's `either { }` and `bind()` sequence them
  like Effect's generators.
- Domain errors live in `domain`. Ports return them alongside
  `PersistenceFailure` in per-method sealed interfaces.
- `PersistenceFailure<Op>` holds the operation and `Diagnostics` (a reason such
  as `DatabaseError`, `PoolTimedOut` or `TimeoutError`, and a SQLSTATE or the
  invalid column). jOOQ's exception is reduced to these where `bounded()`
  catches it, so SQL, parameters and stored values cannot reach a log or a
  response.
- The API maps each error to an `ApiError` with an exhaustive `when`; a
  persistence failure is logged with its safe fields and answered with a 500
  (503 for readiness) that says nothing about it.
- Exceptions are for bugs. StatusPages answers an uncaught one with 500
  `INTERNAL_ERROR` and logs only its class name.

## Concurrency and time limits

Ktor and the worker run on coroutines. JDBC blocks, so `bounded()` runs each
query on `Dispatchers.IO` and waits for it with `withTimeout`. On timeout or
cancellation it calls jOOQ's `Query.cancel()`, which makes pgjdbc send
PostgreSQL a cancel request, and returns without waiting for the blocked
thread; pgjdbc's `socketTimeout` frees that thread if the server never answers.

## Testing

- Unit tests sit in each module's `src/test`.
- Route and job tests use hand-written fakes implementing the ports and Ktor's
  `testApplication`; no mocking library and no database.
- `PostgresTest` runs the adapters against PostgreSQL in a Testcontainers
  container, including a call that times out and is cancelled on the server.
  It is tagged `postgres`, so `./gradlew check` skips it and
  `:database:postgresTest` runs it.

## Code placement

Put code where `ARCHITECTURE.md` says it belongs and keep its dependency
direction: routes and jobs use application ports, never `database`. Only
`api-server` and `worker-server` may depend on `database`;
`checkDependencyDirection` in `build-logic/src/main/kotlin/kotlin-conventions.gradle.kts`
enforces it, along with where jOOQ, pgjdbc, HikariCP, Flyway, Ktor,
kotlinx.serialization and Logback may appear. Add a rule there when a library
should stay in one module. Declare dependencies with `implementation` unless a
module's public signatures use the library's types.

## Errors

- Errors are values: ports and use cases return Arrow's `Either`, with a sealed
  interface listing every way a call fails. Throw only for bugs.
- Never keep a driver exception in an error: run queries through `bounded()`,
  which reduces it to `Diagnostics`, so no SQL, parameter or stored value can
  reach a log or response. Never log a `Throwable`; log its class name.
- Map errors with an exhaustive `when` over the sealed type. `else` branches on
  sealed types and enums fail detekt (`ElseCaseInsteadOfExhaustiveWhen`).
- An ignored return value fails the build (`-Xreturn-value-checker=full`), so
  an `Either` cannot be dropped. Use its value, `bind()` it, or `onLeft` it.
- No `println`, no `!!` outside tests, and `System.getenv` only in `config`.
  Log with SLF4J's fluent API and `addKeyValue`, so fields become JSON keys.

## Values

Domain values have a private constructor and a `parse` returning `Either`.
Request DTOs live in the API, are `@Serializable` (unknown fields are
rejected), and turn into domain values through `parse`. Money, when it comes,
is integer minor units or `BigDecimal`, never `Double`.

## Database

A schema change is a new `V<n>__<name>.sql` in
`modules/database/src/main/resources/db/migration`; never edit an applied one.
Then run `./gradlew :database:generateSchema` (needs Docker) and commit
`modules/database/src/generated`, which is never edited by hand.

## Checks

`scripts/check.sh` must pass before a change is done: `./gradlew check`
(compiler warnings as errors, unit and route tests, detekt with type
resolution, ktlint, the dependency direction), `verifySchema` and the
PostgreSQL tests. `./gradlew spotlessApply` formats. A detekt finding is fixed
in the code; suppress one only with `@Suppress("Rule")` on the narrowest
element and a comment saying why.

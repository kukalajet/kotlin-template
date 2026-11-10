# syntax=docker/dockerfile:1

# One image runs the API (default command), the worker, the one-shot worker
# and migrations. Building it runs `./gradlew check` (formatting, detekt, the
# compiler with warnings as errors, unit and route tests, the dependency
# direction), so the image cannot be built from code that fails them. The
# PostgreSQL tests and verifySchema need Docker, so scripts/check.sh runs them
# instead.

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew check :api-server:installDist :worker-server:installDist --no-daemon --console=plain \
  && mkdir -p /out/lib \
  && cp apps/api-server/build/install/api-server/lib/*.jar /out/lib/ \
  && cp apps/worker-server/build/install/worker/lib/*.jar /out/lib/

# No shell or package manager: a JRE, the jars and CA certificates, run as an
# unprivileged user. The command names the entry point's main class.
FROM gcr.io/distroless/java25-debian13:nonroot
COPY --from=build /out/lib/ /app/lib/
EXPOSE 8000
# --enable-native-access lets Netty load its native transport without the
# JVM's plain-text warning among the JSON log lines.
ENTRYPOINT ["/usr/bin/java", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=75", "-XX:-UsePerfData", \
            "--enable-native-access=ALL-UNNAMED", "-cp", "/app/lib/*"]
CMD ["kotlintemplate.apiserver.MainKt"]

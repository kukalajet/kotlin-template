#!/usr/bin/env bash
# Every check a change must pass. `./gradlew check` is what the Docker build
# runs; the PostgreSQL tests and verifySchema also need Docker for their
# Testcontainers database.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! docker info >/dev/null 2>&1; then
  echo "Docker is required for the PostgreSQL tests and verifySchema." >&2
  exit 1
fi

./gradlew check :database:verifySchema :database:postgresTest --console=plain

#!/usr/bin/env bash
# Starts the Spring Boot service with MongoDB.
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required. Install it from https://maven.apache.org/install.html" >&2
  exit 1
fi
exec mvn spring-boot:run

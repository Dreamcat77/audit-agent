#!/usr/bin/env bash
set -euo pipefail

# F01/F03 shared local-dev launcher.
# Supply all credentials through environment variables; this script never prints them.

: "${AUDIT_DB_URL:?AUDIT_DB_URL is required}"
: "${AUDIT_DB_USERNAME:?AUDIT_DB_USERNAME is required}"
: "${AUDIT_DB_PASSWORD:?AUDIT_DB_PASSWORD is required}"
: "${AUDIT_FILE_STORAGE_ROOT:?AUDIT_FILE_STORAGE_ROOT is required}"

export AUDIT_REDIS_HOST="${AUDIT_REDIS_HOST:-127.0.0.1}"
export AUDIT_REDIS_PORT="${AUDIT_REDIS_PORT:-6379}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================="
echo "  Audit system - local-dev"
echo "=========================================="
echo "Database, Redis and storage settings loaded from the environment."

exec ./mvnw spring-boot:run "-Dspring-boot.run.profiles=local-dev"

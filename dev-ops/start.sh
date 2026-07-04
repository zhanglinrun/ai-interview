#!/usr/bin/env bash
set -euo pipefail

FULL_STACK=""
SKIP_PACKAGE=""

for arg in "$@"; do
  case "$arg" in
    --full-stack) FULL_STACK=1 ;;
    --skip-package) SKIP_PACKAGE=1 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVOPS="$ROOT/dev-ops"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

cd "$DEVOPS"

if [[ -n "$FULL_STACK" ]]; then
  if [[ -z "$SKIP_PACKAGE" ]]; then
    cd "$ROOT/backend"
    mvn clean package -DskipTests
  fi
  cd "$DEVOPS"
  docker compose -f docker-compose-app.yml up -d --build
else
  docker compose -f docker-compose-environment.yml up -d
fi

echo "ai-interview dev dependencies started"

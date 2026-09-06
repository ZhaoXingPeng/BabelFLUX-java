#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

(cd "$repo_root/backend" && mvn -B test)

if [ -f "$repo_root/frontend/package.json" ]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found; cannot build frontend" >&2
    exit 1
  fi

  if [ -f "$repo_root/frontend/package-lock.json" ]; then
    (cd "$repo_root/frontend" && npm ci)
  fi

  (cd "$repo_root/frontend" && npm run test)
  (cd "$repo_root/frontend" && npm run build)
fi

if [ -f "$repo_root/desktop/package.json" ]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found; cannot build desktop" >&2
    exit 1
  fi

  if [ -f "$repo_root/desktop/package-lock.json" ]; then
    (cd "$repo_root/desktop" && npm ci)
  fi

  (cd "$repo_root/desktop" && npm run build)
fi

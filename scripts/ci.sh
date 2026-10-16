#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
main() {
  python3 scripts/ci_pipeline.py prepare
  trap 'python3 scripts/ci_pipeline.py cleanup' EXIT
  for stage in quality backend frontend images browser recovery authentication verify; do
    python3 scripts/ci_pipeline.py "$stage"
  done
}
main

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
check_mode="${1:-fast}"
if [[ "$#" -gt 1 || ( "$check_mode" != fast && "$check_mode" != full ) ]]; then
  echo 'Usage: bash scripts/check.sh [fast|full]' >&2
  exit 2
fi
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/quality_gate.py
git diff --check
if [[ "$check_mode" == full ]]; then
  ./backend/gradlew -p backend --no-daemon --console=plain build
  (cd frontend && npm ci && npm test && npm run build && npm audit --audit-level=moderate)
fi

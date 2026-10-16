#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cleanup_auth_ci() {
  python3 scripts/auth-local.py --ci --stop
}
trap cleanup_auth_ci EXIT
python3 scripts/test-auth.py --prepare-ci
python3 scripts/auth-local.py --ci
if [[ "${CI_IMMUTABLE_IMAGES:-0}" == 1 ]]; then
  python3 scripts/ci_pipeline.py attest-auth
fi
python3 scripts/test-auth.py --ci

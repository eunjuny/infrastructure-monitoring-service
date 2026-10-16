#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm --entrypoint promtool -v "$PWD/infra/prometheus:/rules:ro" -w /rules \
  prom/prometheus:v3.13.1 check rules alerts.yaml
docker run --rm --entrypoint promtool -v "$PWD/infra/prometheus:/rules:ro" -w /rules \
  prom/prometheus:v3.13.1 test rules alerts.test.yaml
docker run --rm --entrypoint amtool -v "$PWD/infra/alertmanager/alertmanager.yaml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.34.0 check-config /etc/alertmanager/alertmanager.yml

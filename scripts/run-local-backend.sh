#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
./backend/gradlew -p backend --no-daemon --console=plain bootJar
mkdir -p .local/runtime
runtime_jar=".local/runtime/monitoring-$(date +%Y%m%d%H%M%S).jar"
cp backend/build/libs/monitoring-service.jar "$runtime_jar"
exec java -jar "$runtime_jar" --spring.profiles.active=mock

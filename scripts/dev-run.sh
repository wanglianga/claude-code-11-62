#!/bin/bash
# Local dev helper: stop any running instance, rebuild, optionally reset schema, start the jar.
set -u
cd "$(dirname "$0")/.." || exit 1
MODE="${1:-keep}"

if [ -f /tmp/ledger.pid ]; then
  kill "$(cat /tmp/ledger.pid)" 2>/dev/null
  for _ in $(seq 1 20); do
    kill -0 "$(cat /tmp/ledger.pid)" 2>/dev/null || break
    sleep 0.5
  done
  rm -f /tmp/ledger.pid
fi

/opt/maven/bin/mvn -q -B -DskipTests package || exit 1

if [ "$MODE" = "reset" ]; then
  PGPASSWORD="${DB_PASSWORD:-ccdev}" /opt/postgresql-18/bin/psql \
    -h "${DB_HOST:-127.0.0.1}" -U "${DB_USER:-ccdev}" -d "${DB_NAME:-ccdev}" \
    -c 'DROP SCHEMA IF EXISTS bonded_ledger CASCADE;' >/dev/null || exit 1
  echo "schema dropped"
fi

nohup /opt/jdk-25/bin/java -jar target/bonded-ledger-1.0.0.jar > /tmp/app.log 2>&1 &
echo $! > /tmp/ledger.pid

for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
  if [ "$code" = "200" ]; then
    echo "app healthy (pid $(cat /tmp/ledger.pid))"
    exit 0
  fi
  sleep 1
done
echo "app did not become healthy"; tail -30 /tmp/app.log; exit 1

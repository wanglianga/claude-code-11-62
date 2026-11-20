#!/usr/bin/env bash
# Replays the sample month-end scenario against a running instance and prints the outcome.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE:-http://127.0.0.1:8080/api/v1}"
PERIOD="${1:-2026-03}"
curl -s -X POST "$BASE/samples/month-end-replay" \
  -H 'Content-Type: application/json' \
  -d "{\"period\":\"$PERIOD\"}" | python3 scripts/print-replay.py

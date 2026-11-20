#!/usr/bin/env bash
# End-to-end check of void/撤销 handling: close, void an adjustment, re-close, verify the balance.
set -euo pipefail
API="${API:-http://127.0.0.1:8080/api/v1}"
L="${LEDGER:-VOID-E2E-$(date +%s)}"
post_doc() {
  local type=$1 ext=$2 ver=$3 time=$4 body=$5 extra=${6:-}
  # shellcheck disable=SC2086
  curl -s -X POST "$API/ledgers/$L/documents" -H 'Content-Type: application/json' \
    -H "X-Doc-Type: $type" -H 'X-Source-System: ERP' -H "X-External-Id: $ext" \
    -H "X-Source-Version: $ver" -H "X-Business-Time: $time" $extra \
    -d "$body" -o /dev/null -w "$type $ext v$ver -> %{http_code}\n"
}

curl -s -X POST "$API/ledgers" -H 'Content-Type: application/json' \
  -d "{\"code\":\"$L\",\"name\":\"void test\",\"zone\":\"Asia/Shanghai\"}" -o /dev/null
curl -s -X POST "$API/ledgers/$L/materials" -H 'Content-Type: application/json' \
  -d '{"code":"AL","name":"Aluminium","baseUnit":"KG"}' -o /dev/null
curl -s -X POST "$API/ledgers/$L/aliases" -H 'Content-Type: application/json' \
  -d '{"sourceSystem":"ERP","sourceItemCode":"AL","material":"AL","validFrom":"2026-01-01"}' -o /dev/null

post_doc DECLARATION D1 1 2026-03-02T09:00:00+08:00 \
  '{"declarationNo":"D1","lines":[{"itemCode":"AL","unit":"KG","quantity":500,"batchNo":"B1"}]}'
post_doc DECLARATION_RECEIPT R1 1 2026-03-02T10:00:00+08:00 \
  '{"declarationNo":"D1","lines":[{"itemCode":"AL","unit":"KG","quantity":500,"batchNo":"B1"}]}'
post_doc ADJUSTMENT A1 1 2026-03-05T10:00:00+08:00 \
  '{"lines":[{"itemCode":"AL","unit":"KG","quantity":300,"batchNo":"B1"}]}'
post_doc ISSUE W1 1 2026-03-10T10:00:00+08:00 \
  '{"lines":[{"itemCode":"AL","unit":"KG","quantity":100,"batchNo":"B1","workOrder":"W1"}]}'

V1=$(curl -s -X POST "$API/ledgers/$L/periods/2026-03/close" -H 'Content-Type: application/json' -d '{}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["closeId"])')
echo "first close id=$V1"
curl -s "$API/ledgers/$L/closes/$V1" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); m=d["materials"][0]; print("v1 adjust",m["adjust"],"closing",m["closing"])'

ADJ_ID=$(curl -s "$API/ledgers/$L/documents/history?sourceSystem=ERP&docType=ADJUSTMENT&externalId=A1" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
echo "voiding adjustment document id=$ADJ_ID"
post_doc VOID "VOID-A1" 1 2026-03-31T10:00:00+08:00 '{"reason":"adjustment entered twice"}' \
  "-H X-Void-Of-Document-Id:$ADJ_ID"

V2=$(curl -s -X POST "$API/ledgers/$L/periods/2026-03/close" -H 'Content-Type: application/json' \
  -d '{"allowOverwrite":true}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["closeId"])')
echo "second close id=$V2"
curl -s "$API/ledgers/$L/closes/$V2" | python3 -c '
import json,sys
d=json.load(sys.stdin)
m=[x for x in d["materials"] if x["material"]=="AL"][0]
print("v2 adjust",m["adjust"],"closing",m["closing"])
print("changes", [c["kind"] for c in d["changes"]])
assert m["adjust"] == 0, "voided adjustment should no longer affect the period"
assert m["closing"] == 400, "500 declared - 100 issued, adjustment voided"
assert any(c["kind"] == "VOIDED" for c in d["changes"])
assert d["close"]["version"] == 2
'

echo "--- v1 still reproducible ---"
curl -s -X POST "$API/ledgers/$L/closes/$V1/reproduce" | python3 -c '
import json,sys
d=json.load(sys.stdin)
b=[x for x in d["balances"] if x["material"]=="AL"][0]
print("hashMatch", d["hashMatch"], "reported closing", b["reportedClosing"])
assert d["hashMatch"]
assert b["reportedClosing"] == 700
'
echo "VOID CHECK OK"

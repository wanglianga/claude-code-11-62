import json
import sys

d = json.load(sys.stdin)
print("ledger:", d.get("ledgerCode"), " period:", d.get("period"), " allPassed:", d.get("allPassed"))
print("\nsteps:")
for s in d.get("steps", []):
    print("  - %-12s %s" % (s["action"], s["detail"]))
print("\nassertions:")
for k, v in d.get("assertions", {}).items():
    print("  %s: %s" % (k, v))
sys.exit(0 if d.get("allPassed") else 1)

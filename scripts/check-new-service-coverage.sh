#!/usr/bin/env bash
# Enforce at least 80% JaCoCo line coverage for classes listed as new service classes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="${ROOT}/config/coverage/new-service-classes.txt"
THRESHOLD="${COVERAGE_LINE_THRESHOLD:-0.80}"

python3 - "${ROOT}" "${LIST}" "${THRESHOLD}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
list_path = Path(sys.argv[2])
threshold = float(sys.argv[3])
reports = {
    "customer-api": root / "backend" / "target" / "site" / "jacoco" / "jacoco.xml",
    "platform-admin-api": root / "backend" / "platform-admin-api" / "target" / "site" / "jacoco" / "jacoco.xml",
}

required = []
for raw in list_path.read_text().splitlines():
    line = raw.split("#", 1)[0].strip()
    if not line:
        continue
    parts = line.split()
    if len(parts) != 2 or parts[0] not in reports:
        print(f"Invalid coverage entry: {raw}", file=sys.stderr)
        print("Expected: <customer-api|platform-admin-api> <fully.qualified.Class>", file=sys.stderr)
        sys.exit(2)
    required.append((parts[0], parts[1]))

if not required:
    print("No new service classes are registered.")
    print("Legacy code is not held to a blanket line-coverage threshold.")
    print(f"Threshold for registered classes: {threshold:.0%} line.")
    sys.exit(0)

indexes = {}
for module, report in reports.items():
    classes = {}
    if report.is_file():
        tree = ET.parse(report)
        for class_el in tree.getroot().iter("class"):
            name = class_el.get("name", "").replace("/", ".")
            line = next((c for c in class_el.findall("counter") if c.get("type") == "LINE"), None)
            if line is None:
                classes[name] = None
            else:
                missed = int(line.get("missed", "0"))
                covered = int(line.get("covered", "0"))
                classes[name] = (covered, missed)
    indexes[module] = classes

failed = False
for module, class_name in required:
    stats = indexes[module].get(class_name)
    report = reports[module]
    if class_name not in indexes[module]:
        print(f"FAIL {module} {class_name}: not found in {report}")
        failed = True
        continue
    if stats is None:
        print(f"PASS {module} {class_name}: no executable lines")
        continue
    covered, missed = stats
    total = covered + missed
    ratio = 1.0 if total == 0 else covered / total
    status = "PASS" if ratio + 1e-9 >= threshold else "FAIL"
    print(f"{status} {module} {class_name}: {ratio:.1%} line ({covered}/{total})")
    if status == "FAIL":
        failed = True

if failed:
    sys.exit(1)
PY

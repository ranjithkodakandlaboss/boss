#!/usr/bin/env bash
# Run SpotBugs with Find Security Bugs. Print every finding.
# Fail when a HIGH finding is new or its count increases. Existing HIGH findings stay in the baseline.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASELINE="${ROOT}/config/spotbugs/baseline.tsv"
WRITE_BASELINE=0
if [[ "${1:-}" == "--write-baseline" ]]; then
  WRITE_BASELINE=1
fi

run_spotbugs() {
  local pom="$1"
  mvn -B -ntp -f "${pom}" -DskipTests -Dspotbugs.failOnError=false -Dspotbugs.threshold=Low compile spotbugs:spotbugs
}

run_spotbugs "${ROOT}/backend/pom.xml"
run_spotbugs "${ROOT}/backend/platform-admin-api/pom.xml"

python3 - "${ROOT}" "${BASELINE}" "${WRITE_BASELINE}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

root = Path(sys.argv[1])
baseline_path = Path(sys.argv[2])
write_baseline = sys.argv[3] == "1"
priority_name = {"1": "HIGH", "2": "MEDIUM", "3": "LOW"}
modules = {
    "customer-api": root / "backend" / "target" / "spotbugsXml.xml",
    "platform-admin-api": root / "backend" / "platform-admin-api" / "target" / "spotbugsXml.xml",
}

counts = Counter()
high = Counter()
details = []
for module, report in modules.items():
    if not report.is_file():
        print(f"Missing SpotBugs report: {report}", file=sys.stderr)
        sys.exit(2)
    tree = ET.parse(report)
    for bug in tree.getroot().findall("BugInstance"):
        priority = bug.get("priority", "")
        severity = priority_name.get(priority, f"PRIORITY_{priority}")
        bug_type = bug.get("type")
        class_el = bug.find("Class")
        class_name = class_el.get("classname") if class_el is not None else ""
        source = bug.find("SourceLine")
        line = source.get("start") if source is not None else ""
        message = (bug.findtext("LongMessage") or "").strip()
        counts[(module, severity)] += 1
        details.append((severity, module, class_name, line, bug_type, message))
        if severity == "HIGH":
            high[(module, bug_type, class_name)] += 1

baseline = Counter()
if baseline_path.is_file():
    for line in baseline_path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        module, bug_type, class_name, count = line.split("\t")
        baseline[(module, bug_type, class_name)] = int(count)

new_items = []
for key, count in sorted(high.items()):
    allowed = baseline.get(key, 0)
    if count > allowed:
        new_items.append((key, count - allowed, count, allowed))

print("SpotBugs severity counts:")
for module in modules:
    for severity in ("HIGH", "MEDIUM", "LOW"):
        print(f"  {module} {severity}: {counts[(module, severity)]}")
print(f"Baseline HIGH findings: {sum(baseline.values())}")
print(f"Current HIGH findings: {sum(high.values())}")
print(f"New HIGH findings: {sum(extra for _, extra, _, _ in new_items)}")
print("Findings:")
if not details:
    print("  (none)")
for severity, module, class_name, line, bug_type, message in sorted(details):
    marker = "INFO"
    if severity == "HIGH":
        allowed = baseline.get((module, bug_type, class_name), 0)
        marker = "NEW" if high[(module, bug_type, class_name)] > allowed else "BASELINE"
    print(f"  {marker}\t{severity}\t{module}\t{class_name}:{line}\t{bug_type}\t{message}")

if write_baseline:
    baseline_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Existing HIGH SpotBugs / Find Security Bugs findings. This is a baseline, not a suppression.",
        "# The gate prints every finding and fails when a HIGH count rises or a new HIGH key appears.",
        "# Format: module<TAB>bugType<TAB>className<TAB>count",
    ]
    for (module, bug_type, class_name), count in sorted(high.items()):
        lines.append(f"{module}\t{bug_type}\t{class_name}\t{count}")
    baseline_path.write_text("\n".join(lines) + "\n")
    print(f"Wrote {baseline_path}")
    sys.exit(0)

if new_items:
    print("New HIGH SpotBugs findings:", file=sys.stderr)
    for (module, bug_type, class_name), extra, count, allowed in new_items:
        print(f"  {module} {class_name} {bug_type}: {count} current, {allowed} baseline, {extra} new", file=sys.stderr)
    sys.exit(1)
PY

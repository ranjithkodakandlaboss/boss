#!/usr/bin/env bash
# Report PMD maintainability findings and fail on violations that are not in the baseline.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASELINE="${ROOT}/config/pmd/baseline.tsv"
WRITE_BASELINE=0
if [[ "${1:-}" == "--write-baseline" ]]; then
  WRITE_BASELINE=1
fi

run_pmd() {
  local pom="$1"
  mvn -B -ntp -f "${pom}" pmd:pmd
}

run_pmd "${ROOT}/backend/pom.xml"
run_pmd "${ROOT}/backend/platform-admin-api/pom.xml"

python3 - "${ROOT}" "${BASELINE}" "${WRITE_BASELINE}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

root = Path(sys.argv[1])
baseline_path = Path(sys.argv[2])
write_baseline = sys.argv[3] == "1"
modules = {
    "customer-api": root / "backend",
    "platform-admin-api": root / "backend" / "platform-admin-api",
}

current = Counter()
details = []
for module, base in modules.items():
    report = base / "target" / "pmd.xml"
    if not report.is_file():
        print(f"Missing PMD report: {report}", file=sys.stderr)
        sys.exit(2)
    tree = ET.parse(report)
    ns = {"p": "http://pmd.sourceforge.net/report/2.0.0"}
    file_els = tree.getroot().findall("p:file", ns)
    if not file_els:
        file_els = tree.getroot().findall("file")
    for file_el in file_els:
        name = file_el.get("name")
        rel = str(Path(name).resolve().relative_to(base.resolve()))
        violations = file_el.findall("p:violation", ns) or file_el.findall("violation")
        for violation in violations:
            rule = violation.get("rule")
            current[(module, rel, rule)] += 1
            details.append((module, rel, rule, violation.get("beginline"), (violation.text or "").strip()))

baseline = Counter()
if baseline_path.is_file():
    for line in baseline_path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        module, rel, rule, count = line.split("\t")
        baseline[(module, rel, rule)] = int(count)

new_items = []
for key, count in sorted(current.items()):
    allowed = baseline.get(key, 0)
    if count > allowed:
        new_items.append((key, count - allowed, count, allowed))

print(f"Baseline findings: {sum(baseline.values())}")
print(f"Current findings: {sum(current.values())}")
print(f"New findings: {sum(extra for _, extra, _, _ in new_items)}")
print("Current findings:")
if not details:
    print("  (none)")
for module, rel, rule, line, message in sorted(details):
    allowed = baseline.get((module, rel, rule), 0)
    marker = "NEW" if current[(module, rel, rule)] > allowed else "BASELINE"
    print(f"  {marker}\t{module}\t{rel}:{line}\t{rule}\t{message}")

if write_baseline:
    lines = [
        "# Existing PMD maintainability findings. This list is a baseline, not a suppression.",
        "# The gate prints every current finding and fails when a count rises or a new key appears.",
        "# Format: module<TAB>path<TAB>rule<TAB>count",
    ]
    for (module, rel, rule), count in sorted(current.items()):
        lines.append(f"{module}\t{rel}\t{rule}\t{count}")
    baseline_path.write_text("\n".join(lines) + "\n")
    print(f"Wrote {baseline_path}")
    sys.exit(0)

if new_items:
    print("New maintainability violations:", file=sys.stderr)
    for (module, rel, rule), extra, count, allowed in new_items:
        print(f"  {module} {rel} {rule}: {count} current, {allowed} baseline, {extra} new", file=sys.stderr)
    sys.exit(1)
PY

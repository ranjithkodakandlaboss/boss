#!/usr/bin/env bash
# Print every CRITICAL Trivy finding. Fail unless each one is a documented exception.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <trivy-json>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "${ROOT}/config/security/trivy-release-exceptions.tsv" "$1" <<'PY'
import json
import sys
from pathlib import Path

exceptions = {}
for raw in Path(sys.argv[1]).read_text().splitlines():
    line = raw.split("#", 1)[0].strip()
    if not line:
        continue
    cve, package, reason = line.split("\t", 2)
    exceptions[(cve, package)] = reason

report = json.loads(Path(sys.argv[2]).read_text())
findings = []
for result in report.get("Results") or []:
    for vuln in result.get("Vulnerabilities") or []:
        if vuln.get("Severity") != "CRITICAL":
            continue
        findings.append(vuln)

print(f"CRITICAL findings: {len(findings)}")
unexpected = []
seen = set()
for vuln in findings:
    cve = vuln.get("VulnerabilityID", "")
    package = vuln.get("PkgName", "")
    key = (cve, package)
    fixed = vuln.get("FixedVersion") or "none"
    installed = vuln.get("InstalledVersion", "")
    reason = exceptions.get(key)
    if reason:
        seen.add(key)
        print(f"RELEASE EXCEPTION {cve} {package} {installed} fixed {fixed}")
        print(f"  {reason}")
    else:
        unexpected.append(key)
        print(f"CRITICAL {cve} {package} {installed} fixed {fixed}")

unused = sorted(set(exceptions) - seen)
for cve, package in unused:
    print(f"EXCEPTION NOT PRESENT {cve} {package}")

if unexpected:
    print(f"Unexpected CRITICAL findings: {len(unexpected)}", file=sys.stderr)
    sys.exit(1)
print("CRITICAL gate passed")
PY

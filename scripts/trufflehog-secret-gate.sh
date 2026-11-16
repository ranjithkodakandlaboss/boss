#!/usr/bin/env bash
# Fail on every TruffleHog result except the exact local JDBC placeholders.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <trufflehog-json>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "${ROOT}/config/security/trufflehog-localhost-jdbc.txt" "$1" <<'PY'
import json
import sys
from pathlib import Path

allowed = set()
for raw in Path(sys.argv[1]).read_text().splitlines():
    line = raw.split("#", 1)[0].strip()
    if line:
        allowed.add(line)

allowed_count = 0
failures = []
for raw_line in Path(sys.argv[2]).read_text().splitlines():
    line = raw_line.strip()
    if not line.startswith("{"):
        continue
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        continue
    detector = obj.get("DetectorName")
    if not detector:
        continue
    secret = obj.get("Raw") or ""
    verified = bool(obj.get("Verified"))
    git = ((obj.get("SourceMetadata") or {}).get("Data") or {}).get("Git") or {}
    where = git.get("file") or "commit-message"
    commit = (git.get("commit") or "")[:12]
    if (
        not verified
        and detector == "JDBC"
        and secret in allowed
        and "password=" not in secret.lower()
        and "@" not in secret
    ):
        allowed_count += 1
        print(f"ALLOWED {detector} {secret} {where} {commit}")
        continue
    failures.append((detector, verified, secret, where, commit))

print(f"allowed local JDBC results: {allowed_count}")
for detector, verified, secret, where, commit in failures:
    state = "verified" if verified else "unverified"
    print(f"SECRET {state} {detector} {secret} {where} {commit}", file=sys.stderr)
if failures:
    print(f"secret scan failed: {len(failures)} result(s)", file=sys.stderr)
    sys.exit(1)
print("secret scan passed")
PY

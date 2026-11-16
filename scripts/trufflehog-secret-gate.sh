#!/usr/bin/env bash
# Fail on every TruffleHog result except the exact local JDBC placeholders.
# Prints detector, location, and commit only.
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

allowlist_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])


def reject(message):
    print(message, file=sys.stderr)
    sys.exit(1)


try:
    allowlist_text = allowlist_path.read_text()
except OSError:
    reject("secret scan failed: allowlist is unreadable")

allowed = set()
for raw in allowlist_text.splitlines():
    line = raw.split("#", 1)[0].strip()
    if line:
        allowed.add(line)
if not allowed:
    reject("secret scan failed: allowlist is empty")

try:
    report_text = report_path.read_text()
except OSError:
    reject("secret scan failed: scanner output is unreadable")

allowed_count = 0
rejected_count = 0
for line_number, raw_line in enumerate(report_text.splitlines(), start=1):
    if raw_line.strip() == "":
        continue
    try:
        obj = json.loads(raw_line)
    except json.JSONDecodeError:
        reject(f"secret scan failed: malformed JSON on line {line_number}")
    if not isinstance(obj, dict):
        reject(f"secret scan failed: unexpected scanner object on line {line_number}")

    if "DetectorName" not in obj:
        reject(f"secret scan failed: unexpected scanner object on line {line_number}")
    detector = obj["DetectorName"]
    if not isinstance(detector, str) or not detector:
        reject(f"secret scan failed: missing DetectorName on line {line_number}")
    if "Raw" not in obj or not isinstance(obj["Raw"], str) or obj["Raw"] == "":
        reject(f"secret scan failed: missing Raw on line {line_number}")
    if "Verified" not in obj or not isinstance(obj["Verified"], bool):
        reject(f"secret scan failed: missing Verified on line {line_number}")

    metadata = obj.get("SourceMetadata")
    git = metadata.get("Data", {}).get("Git") if isinstance(metadata, dict) else None
    if not isinstance(git, dict):
        reject(f"secret scan failed: missing git metadata on line {line_number}")
    commit = git.get("commit")
    if not isinstance(commit, str) or not commit:
        reject(f"secret scan failed: missing commit on line {line_number}")
    location = git.get("file", "commit-message")
    if location != "commit-message" and (not isinstance(location, str) or not location):
        reject(f"secret scan failed: missing file on line {line_number}")
    if "file" not in git:
        location = "commit-message"

    secret = obj["Raw"]
    verified = obj["Verified"]
    commit_ref = commit[:12]
    allowed_placeholder = (
        not verified
        and detector == "JDBC"
        and secret in allowed
        and "password=" not in secret.lower()
        and "@" not in secret
    )
    if allowed_placeholder:
        allowed_count += 1
        print(f"ALLOWED detector={detector} location={location} commit={commit_ref}")
        continue
    rejected_count += 1
    state = "verified" if verified else "unverified"
    print(
        f"REJECTED detector={detector} status={state} location={location} commit={commit_ref}",
        file=sys.stderr,
    )

if rejected_count:
    print(f"secret scan failed: {rejected_count} result(s)", file=sys.stderr)
    sys.exit(1)
print(f"allowed local JDBC results: {allowed_count}")
print("secret scan passed")
PY

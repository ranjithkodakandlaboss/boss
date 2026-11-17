#!/usr/bin/env bash
# Regression checks for the secret gate. Output must not contain raw secret values.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATE="${ROOT}/scripts/trufflehog-secret-gate.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

LOCAL_BOSS='jdbc:postgresql://localhost:5432/boss'
LOCAL_TEST='jdbc:postgresql://localhost:5432/boss_test'
LOCAL_FLYWAY='jdbc:postgresql://localhost:5432/test_flyway_v001_v010'
LOCAL_ELLIPSIS='jdbc:postgresql://localhost:...'
PASSWORD_URL="$(printf '%s' 'jdbc:' 'postgresql://localhost:5432/boss?user=boss&' 'password=' 'LocalOnlyValue')"
REMOTE_URL="$(printf '%s' 'jdbc:' 'postgresql://' 'db.internal.example' ':5432/boss')"
OTHER_SECRET="$(printf '%s' 'AKIA' 'IOSFODNN7' 'EXAMPLE')"

assert_clean() {
  local output_file="$1"
  shift
  local secret
  for secret in "$@"; do
    if grep -F -q -- "$secret" "$output_file"; then
      echo "FAIL output contained a raw secret value" >&2
      exit 1
    fi
  done
}

run_gate() {
  local input_file="$1"
  local expected="$2"
  shift 2
  local output_file="${TMP}/output.txt"
  set +e
  "${GATE}" "$input_file" >"$output_file" 2>&1
  local status=$?
  set -e
  if [ "$status" -ne "$expected" ]; then
    echo "FAIL expected exit ${expected} but got ${status}" >&2
    exit 1
  fi
  assert_clean "$output_file" "$LOCAL_BOSS" "$LOCAL_TEST" "$LOCAL_FLYWAY" "$LOCAL_ELLIPSIS" "$PASSWORD_URL" "$REMOTE_URL" "$OTHER_SECRET"
}

python3 - "$TMP" "$LOCAL_BOSS" "$LOCAL_TEST" "$LOCAL_FLYWAY" "$LOCAL_ELLIPSIS" "$PASSWORD_URL" "$REMOTE_URL" "$OTHER_SECRET" <<'PY'
import json
import sys
from pathlib import Path

tmp = Path(sys.argv[1])
local_boss, local_test, local_flyway, local_ellipsis, password_url, remote_url, other_secret = sys.argv[2:]

def finding(detector, secret, commit, path=None, verified=False, omit=()):
    git = {"commit": commit}
    if path is not None:
        git["file"] = path
    obj = {
        "DetectorName": detector,
        "Raw": secret,
        "RawV2": secret,
        "Verified": verified,
        "SourceMetadata": {"Data": {"Git": git}},
    }
    for key in omit:
        obj.pop(key, None)
        if key == "commit":
            git.pop("commit", None)
        if key == "git":
            obj["SourceMetadata"] = {}
    return json.dumps(obj)

known = [
    ("JDBC", local_boss, "36b1c7053943e859f6a4dac4c261958158e6cd10", "backend/src/test/java/in/boss/shared/flyway/FlywayBootstrapTest.java"),
    ("JDBC", local_boss, "76caa7edc3d18c7a950b9d0e71712a4047fc0c9e", "backend/src/test/java/in/boss/shared/flyway/FlywayBootstrapTest.java"),
    ("JDBC", local_flyway, "6898f4abd0cbe0ad6056b9b3927395371d7f7979", "backend/src/test/java/in/boss/shared/flyway/FlywayBootstrapTest.java"),
    ("JDBC", local_flyway, "97f39ce8cfe1d3150dc6f8ef442d7f80d57b7616", "backend/src/test/java/in/boss/shared/flyway/FlywayBootstrapTest.java"),
    ("JDBC", local_boss, "23a14ed02dc7c1412ea9dedcac2031a65fb28f31", "backend/platform-admin-api/README.md"),
    ("JDBC", local_boss, "23a14ed02dc7c1412ea9dedcac2031a65fb28f31", "backend/platform-admin-api/src/main/resources/application.yml"),
    ("JDBC", local_ellipsis, "1290005d18875cd62fa64f073b5a80dc911552bb", None),
    ("JDBC", local_boss, "c02f2ec60b062df35dc369d2af01fe4d99f796e0", "backend/platform-admin-api/README.md"),
    ("JDBC", local_boss, "c02f2ec60b062df35dc369d2af01fe4d99f796e0", "backend/platform-admin-api/src/main/resources/application.yml"),
    ("JDBC", local_boss, "710c033ddc01a4109bd4858b0d9acab7417aab5b", "backend/src/main/resources/application.yml"),
    ("JDBC", local_boss, "710c033ddc01a4109bd4858b0d9acab7417aab5b", "backend/src/main/resources/application-local.yml"),
    ("JDBC", local_test, "710c033ddc01a4109bd4858b0d9acab7417aab5b", ".github/workflows/ci.yml"),
    ("JDBC", local_boss, "710c033ddc01a4109bd4858b0d9acab7417aab5b", "DEVELOPER_SETUP.md"),
]
(tmp / "known.json").write_text("".join(finding(*row) + "\n" for row in known))
(tmp / "password.json").write_text(finding("JDBC", password_url, "a" * 40, "app.yml") + "\n")
(tmp / "remote.json").write_text(finding("JDBC", remote_url, "b" * 40, "app.yml") + "\n")
(tmp / "other.json").write_text(finding("Postgres", other_secret, "c" * 40, "app.yml") + "\n")
(tmp / "malformed.json").write_text("{not-json\n")
(tmp / "unexpected.json").write_text('{"msg":"finished scanning","logger":"trufflehog"}\n')
(tmp / "missing.json").write_text(finding("JDBC", local_boss, "d" * 40, "app.yml", omit=("Raw",)) + "\n")
(tmp / "not-object.json").write_text("[]\n")
PY

run_gate "${TMP}/known.json" 0
run_gate "${TMP}/password.json" 1
run_gate "${TMP}/remote.json" 1
run_gate "${TMP}/other.json" 1
run_gate "${TMP}/malformed.json" 1
run_gate "${TMP}/unexpected.json" 1
run_gate "${TMP}/missing.json" 1
run_gate "${TMP}/not-object.json" 1

echo "secret gate regression tests passed"

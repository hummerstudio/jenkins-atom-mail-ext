#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/target/surefire-reports"
printf '<testsuite tests="1" failures="1"><testcase classname="demo.A" name="red"><failure message="boom"/></testcase></testsuite>' >"$dir/target/surefire-reports/TEST-a.xml"
printf 'line one\nERROR broke\n' >"$dir/build.log"
printf '{"to": "dev@example.com", "dryRun": "true", "subject": "#$BUILD_NUMBER $BUILD_STATUS"}' >"$dir/input.json"
run "$dir" atom_build_log="$dir/build.log" BUILD_NUMBER=12 BUILD_STATUS=FAILURE JOB_NAME=parcel >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success"
eml=$(find "$dir" -name message.eml | head -1)
[ -n "$eml" ] || fail "no message.eml"
python3 - "$eml" <<'PY'
import email, sys
msg = email.message_from_bytes(open(sys.argv[1], 'rb').read())
body = msg.get_payload(decode=True).decode()
assert "#12 FAILURE" in msg['Subject'], msg['Subject']
assert "line one" in body, body
assert "demo.A#red" in body, body
PY

dir="$(mktemp -d)"; printf '{"to": ""}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "empty recipient must fail"
grep -q "no recipient" "$dir/output.json" || fail "message"

echo "smoke ok: subject tokens, log excerpt, failed tests, missing recipient"

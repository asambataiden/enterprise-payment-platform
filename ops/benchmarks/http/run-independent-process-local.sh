#!/usr/bin/env bash
# Opt-in, bounded synthetic loopback 100-RPS 60s client/server process lab.
set -Eeuo pipefail
for cmd in java mvn python3 git docker; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 2; }
done
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$dir" rev-parse --show-toplevel)"
docker info >/dev/null 2>&1 || { echo 'Docker Desktop unavailable' >&2; exit 2; }
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
prefix="$out/$id"
mvn_log="$prefix-independent-maven.log"
client="$prefix-independent-client.csv"
server="$prefix-independent-server.csv"
report="$prefix-independent-analysis.log"
gc_log="$prefix-independent-server-jvm.log"
jvm_temp_dir="$(mktemp -d /tmp/enterprise-payment-independent.XXXXXXXX)"
gc_pattern="$jvm_temp_dir/gc-%p.log"
cleanup() {
  status=$?
  if [[ "$status" -ne 0 ]]; then
    for candidate in "$jvm_temp_dir"/gc-*.log; do
      [[ -f "$candidate" ]] || continue
      cp "$candidate" "$out/$id-$(basename "$candidate")" || true
    done
  fi
  rm -rf "$jvm_temp_dir"
}
trap cleanup EXIT
# Validate unified JVM logging in an isolated preflight Java process.
if ! JAVA_TOOL_OPTIONS="-Xlog:gc*,safepoint:file=$gc_pattern:uptimemillis,level,tags" \
    java -version >/dev/null 2>&1; then
  echo 'JVM unified logging preflight failed' >&2
  exit 2
fi
for candidate in "$jvm_temp_dir"/gc-*.log; do
  if [[ -f "$candidate" ]]; then rm -f "$candidate"; fi
done
printf 'Milestone 24 — independent Python HTTP client and Spring Boot JVM\nUTC: %s\nSHA: %s\n' \
  "$(date -u +%FT%TZ)" "$(git -C "$root" rev-parse HEAD)"
printf 'Exactly 6000 synthetic requests at 100 RPS; loopback only.\n'
python3 -m py_compile "$root/ops/benchmarks/http/independent-client-local.py"
python3 -m py_compile "$root/ops/benchmarks/http/analyze-independent-process.py"
python3 "$root/ops/benchmarks/http/test_independent_process.py"

if ! (cd "$root" && \
    JAVA_TOOL_OPTIONS="-Xlog:gc*,safepoint:file=$gc_pattern:uptimemillis,level,tags" \
    mvn -B --no-transfer-progress -f "$root/pom.xml" \
    -Dmixed.lab.rate=100 -Dmixed.lab.duration.seconds=60 \
    -Dmixed.lab.seed.records=128 \
    -Dmixed.lab.independent.client=true \
    "-Dmixed.lab.independent.output=$prefix" \
    -Dtest=MixedHttpWorkloadLab test) >"$mvn_log" 2>&1; then
  grep -E 'INDEPENDENT_|BUILD FAILURE|Tests run:|ERROR|Exception' "$mvn_log" | tail -n 70 || true
  echo "FAIL: independent-process experiment; inspect $mvn_log" >&2
  exit 1
fi
grep -E 'INDEPENDENT_|Tests run:|BUILD SUCCESS' "$mvn_log" | tail -n 30 || true
test_pid="$(sed -n 's/.*INDEPENDENT_LAB server_jvm_pid=\([0-9][0-9]*\).*/\1/p' "$mvn_log")"
if [[ ! "$test_pid" =~ ^[0-9]+$ ]]; then
  echo 'FAIL: missing unique test-server JVM PID' >&2
  exit 1
fi
test_gc="$jvm_temp_dir/gc-$test_pid.log"
if [[ ! -s "$test_gc" ]]; then
  echo "FAIL: missing test JVM GC log for PID $test_pid" >&2
  exit 1
fi
cp "$test_gc" "$gc_log"
[[ -f "$client" && -f "$server" ]] || {
  echo 'FAIL: client/server evidence missing' >&2
  exit 1
}
python3 "$root/ops/benchmarks/http/analyze-independent-process.py" \
  "$client" "$server" | tee "$report"
printf 'Evidence: %s, %s, %s, %s, %s\n' "$mvn_log" "$client" "$server" "$gc_log" "$report"

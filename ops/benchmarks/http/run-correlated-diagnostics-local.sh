#!/usr/bin/env bash
# Explicit opt-in: one isolated synthetic 100 RPS / 60 sec stage, not production.
set -Eeuo pipefail
for required in java mvn git docker python3; do
  command -v "$required" >/dev/null 2>&1 || { echo "Missing: $required" >&2; exit 2; }
done
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$script_dir" rev-parse --show-toplevel)"
docker info >/dev/null 2>&1 || { echo 'Docker Desktop unavailable' >&2; exit 2; }
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
log="$out/$id-correlated-maven.log"
csv="$out/$id-correlated-requests.csv"
gc="$out/$id-correlated-jvm.log"
analysis="$out/$id-correlated-analysis.log"
printf 'Milestone 22: isolated localhost GC/safepoint and client timing diagnostics\nGit SHA: %s\nUTC: %s\n' \
  "$(git -C "$root" rev-parse HEAD)" "$(date -u +%FT%TZ)"
printf 'HTTP rate=100 RPS duration=60s merchants=4 records_per_merchant=128\n'
printf 'Maven log: %s\nCSV: %s\nGC/safepoint log: %s\n' "$log" "$csv" "$gc"

# JAVA_TOOL_OPTIONS is inherited by Maven's process AND its forked
# Surefire test JVM. The %p placeholder produces separate files per PID.
# Never accept a Maven-process or java -version log as test-JVM evidence.
jvm_temp_dir="$(mktemp -d /tmp/enterprise-payment-jvm.XXXXXXXX)" || {
  echo 'Cannot create space-free JVM diagnostic directory' >&2
  exit 2
}
jvm_log_pattern="$jvm_temp_dir/diagnostic-%p.log"
preserve_jvm_logs() {
  status=$?
  if [[ "$status" -ne 0 ]]; then
    for source in "$jvm_temp_dir"/diagnostic-*.log; do
      [[ -f "$source" ]] || continue
      cp "$source" "$out/$id-$(basename "$source")" || true
    done
    echo "Diagnostic failure: any JVM logs retained under $out" >&2
  fi
  rm -rf "$jvm_temp_dir"
}
trap preserve_jvm_logs EXIT

# Test this JVM option, then REMOVE all preflight logs. It does not prove
# the test JVM is instrumented; that proof comes from matching its PID.
if ! JAVA_TOOL_OPTIONS="-Xlog:gc*,safepoint:file=$jvm_log_pattern:uptimemillis,level,tags" \
    java -version >/dev/null 2>&1; then
  echo 'JVM unified logging option not supported; no traffic generated' >&2
  exit 2
fi
for source in "$jvm_temp_dir"/diagnostic-*.log; do
  if [[ -f "$source" ]]; then rm -f "$source"; fi
done

if ! (cd "$root" && \
    JAVA_TOOL_OPTIONS="-Xlog:gc*,safepoint:file=$jvm_log_pattern:uptimemillis,level,tags" \
    mvn -B --no-transfer-progress -f "$root/pom.xml" \
    -Dmixed.lab.rate=100 -Dmixed.lab.duration.seconds=60 \
    -Dmixed.lab.seed.records=128 \
    "-Dmixed.lab.csv.path=$csv" \
    -Dtest=MixedHttpWorkloadLab test) >"$log" 2>&1; then
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 70 || true
  echo 'STOP: HTTP workload failed. Inspect Maven and diagnostic logs.' >&2
  exit 1
fi
grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" || true

test_jvm_pid="$(sed -n 's/.*MIXED_HTTP_LAB test_jvm_pid=\\([0-9][0-9]*\\).*/\\1/p' "$log")"
if [[ ! "$test_jvm_pid" =~ ^[0-9]+$ ]]; then
  echo 'Evidence failure: one test JVM PID was not recorded' >&2
  exit 1
fi
test_jvm_log="$jvm_temp_dir/diagnostic-$test_jvm_pid.log"
if [[ ! -s "$test_jvm_log" ]]; then
  echo "Evidence failure: missing JVM log for Surefire test PID $test_jvm_pid" >&2
  echo 'Maven may have succeeded, but the logging evidence is incomplete.' >&2
  exit 1
fi
cp "$test_jvm_log" "$gc"
[[ -s "$gc" && -f "$csv" ]] || { echo 'Missing GC or CSV evidence' >&2; exit 1; }
[[ "$(awk 'END{print NR}' "$csv")" -eq 6001 ]] || { echo 'CSV row count mismatch' >&2; exit 1; }
python3 "$root/ops/benchmarks/http/analyze-correlated-diagnostics.py" "$csv" "$gc" "$log" | tee "$analysis"
printf '\nEvidence preserved: %s %s %s %s\n' "$log" "$csv" "$gc" "$analysis"

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

# The Maven project preserves argLine for forked Surefire JVMs.
# Supplying only explicit synthetic evidence file paths avoids secret leakage.
# Execute from the repository root: Surefire JVM args split on spaces,
# so the -Xlog file target MUST be a relative path without spaces.
# The project itself may reside under a directory with spaces on macOS.
if ! (cd "$root" && mvn -B --no-transfer-progress -f "$root/pom.xml" \
    -Dmixed.lab.rate=100 -Dmixed.lab.duration.seconds=60 \
    -Dmixed.lab.seed.records=128 \
    "-Dmixed.lab.csv.path=$csv" \
    "-DargLine=-Xlog:gc*,safepoint:file=target/http-load-lab/$id-correlated-jvm.log:uptimemillis,level,tags" \
    -Dtest=MixedHttpWorkloadLab test) >"$log" 2>&1; then
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 70 || true
  echo 'STOP: isolated stage failed. Full evidence kept.' >&2
  exit 1
fi
grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" || true
[[ -f "$gc" && -f "$csv" ]] || { echo 'Missing GC or CSV evidence' >&2; exit 1; }
[[ "$(awk 'END{print NR}' "$csv")" -eq 6001 ]] || { echo 'CSV row count mismatch' >&2; exit 1; }
python3 "$root/ops/benchmarks/http/analyze-correlated-diagnostics.py" "$csv" "$gc" "$log" | tee "$analysis"
printf '\nEvidence preserved: %s %s %s %s\n' "$log" "$csv" "$gc" "$analysis"

#!/usr/bin/env bash
# Explicit opt-in ONLY. Bounded localhost HTTP / disposable PostgreSQL lab.
set -Eeuo pipefail
for required in java mvn git docker; do
  command -v "$required" >/dev/null 2>&1 || {
    printf 'Missing required command: %s\n' "$required" >&2
    exit 2
  }
done
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$script_dir" rev-parse --show-toplevel)"
docker info >/dev/null 2>&1 || {
  echo 'Docker Desktop is unavailable.' >&2
  exit 2
}
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
summary="$out/$id-large-corpus-summary.log"
{
  echo 'Milestone 21 — isolated large-corpus per-request evidence'
  echo "UTC: $(date -u +%FT%TZ)"
  echo "Git SHA: $(git -C "$root" rev-parse HEAD)"
  echo '4 merchants; 128 seeded simulated payment records per merchant'
  echo 'Exactly one 100-RPS x 60-second stage; 6000 measured calls'
  echo '80% GET / 20% POST; corrected 70/10/10/10 distribution'
  echo '8 HTTP workers, 32 max outstanding; Hikari max size 4'
  echo 'CSV contains synthetic operation/merchant-index/status/latency only'
  echo 'No real payment systems, external endpoints or production secrets'
} | tee "$summary"

log="$out/$id-large-corpus-100rps-60s.log"
csv="$out/$id-large-corpus-100rps-60s.csv"
printf '\nDetailed Maven log: %s\nRaw request CSV: %s\n' "$log" "$csv" | tee -a "$summary"

if ! mvn -B --no-transfer-progress -f "$root/pom.xml" \
    -Dmixed.lab.rate=100 -Dmixed.lab.duration.seconds=60 \
    -Dmixed.lab.seed.records=128 \
    "-Dmixed.lab.csv.path=$csv" \
    -Dtest=MixedHttpWorkloadLab test >"$log" 2>&1; then
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 80 | tee -a "$summary" || true
  echo 'STOP: load stage failed; no results are certified.' | tee -a "$summary" >&2
  exit 1
fi
grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" | tee -a "$summary" || true
# The CSV is an evidence contract. A missing or truncated file must fail the run.
if [[ ! -f "$csv" ]]; then
  echo 'ERROR: expected CSV missing.' | tee -a "$summary" >&2
  exit 1
fi
rows="$(awk 'END {print NR}' "$csv")"
if [[ "$rows" -ne 6001 ]]; then
  printf 'ERROR: CSV has %s lines, expected header + 6000 = 6001.\n' "$rows" | tee -a "$summary" >&2
  exit 1
fi
printf '\nPASS: one isolated synthetic stage, 6000 CSV rows.\nLog: %s\nCSV: %s\n' "$log" "$csv" | tee -a "$summary"

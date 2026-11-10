#!/usr/bin/env bash
# Explicit, bounded multi-tenant repeatability experiment. Abort on first failure.
set -Eeuo pipefail

for required in java mvn git docker; do
  command -v "$required" >/dev/null 2>&1 || { echo "Missing: $required" >&2; exit 2; }
done
root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
summary="$out/$id-multitenant-summary.log"

{
  echo "Multi-tenant paced HTTP ladder: 25/50/100 then 100/50/25 RPS"
  echo "UTC: $(date -u +%FT%TZ)"
  echo "Git revision: $(git -C "$root" rev-parse HEAD)"
  echo "Six independent 12-second stages, 20 warm-up requests each"
  echo "4 merchants x 8 seeded payments, 70/10/10/10 tenant skew"
  echo "80% GET / 20% POST; synthetic only; loopback; disposable PostgreSQL"
  echo "8 workers, 32 max outstanding; stop immediately on test failure"
} | tee "$summary"

trial=0
for rate in 25 50 100 100 50 25; do
  trial=$((trial + 1))
  log="$out/$id-trial-$trial-rate-$rate.log"
  printf '\nTrial %d rate=%s evidence=%s\n' "$trial" "$rate" "$log" | tee -a "$summary"
  if ! mvn -B --no-transfer-progress -f "$root/pom.xml" \
       "-Dmixed.lab.rate=$rate" -Dtest=MixedHttpWorkloadLab test >"$log" 2>&1; then
    grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 35 | tee -a "$summary" || true
    printf 'STOP at trial %d; no further traffic. Full evidence: %s\n' "$trial" "$log" | tee -a "$summary" >&2
    exit 1
  fi
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" | tee -a "$summary" || true
done
printf '\nMulti-tenant repeatability ladder complete: %s\n' "$summary"

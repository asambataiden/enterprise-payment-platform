#!/usr/bin/env bash
# Isolated, explicit opt-in 25 -> 50 -> 100 requests/sec ladder.
# Every rate starts its own Spring context and disposable PostgreSQL container.
set -Eeuo pipefail

for required in java mvn git docker; do
  command -v "$required" >/dev/null 2>&1 || { echo "Missing: $required" >&2; exit 2; }
done
root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
mkdir -p "$root/target/http-load-lab"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
summary="$root/target/http-load-lab/$id-rate-ladder-summary.log"

{
  echo "Rate ladder: 25 -> 50 -> 100 req/sec; 12 seconds per step."
  echo "Git revision: $(git -C "$root" rev-parse HEAD)"
  echo "UTC: $(date -u +%FT%TZ)"
  echo "Each test is isolated: 127.0.0.1 signed JWT Spring API and disposable PostgreSQL."
  echo "Hard request caps: 300, 600, 1200; 8 workers, 32 outstanding maximum."
} | tee "$summary"

for rate in 25 50 100; do
  log="$root/target/http-load-lab/$id-rate-$rate.log"
  printf '\nStarting rate=%s, evidence=%s\n' "$rate" "$log" | tee -a "$summary"
  # Stop immediately if any gate fails. Do not proceed to a higher load.
  if ! mvn -B --no-transfer-progress -f "$root/pom.xml" \
        "-Dmixed.lab.rate=$rate" -Dtest=MixedHttpWorkloadLab test >"$log" 2>&1; then
    grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 35 | tee -a "$summary" || true
    printf 'STOP: rate %s failed; higher rates NOT attempted. Review %s\n' "$rate" "$log" | tee -a "$summary" >&2
    exit 1
  fi
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" | tee -a "$summary" || true
done
printf '\nRate ladder complete: %s\n' "$summary"

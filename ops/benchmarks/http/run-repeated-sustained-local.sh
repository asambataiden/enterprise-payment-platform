#!/usr/bin/env bash
# Opt-in repeated endurance observation; only synthetic 127.0.0.1 traffic.
# Each trial has its own disposable PostgreSQL and Spring context.
set -Eeuo pipefail

for required in java mvn git docker; do
  command -v "$required" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$required" >&2
    exit 2
  }
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$script_dir" rev-parse --show-toplevel)"
docker info >/dev/null 2>&1 || {
  echo 'Docker Desktop is not ready.' >&2
  exit 2
}
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
summary="$out/$id-repeat-100rps-summary.log"

{
  echo 'Repeated synthetic merchant fairness test — two 100-RPS x 60-second trials'
  echo "Git SHA: $(git -C "$root" rev-parse HEAD)"
  echo "UTC: $(date -u +%FT%TZ)"
  echo 'Each trial: 6000 measured requests (4800 GET / 1200 POST)'
  echo 'Expected per trial, merchant 0: 3360 GET / 840 POST'
  echo 'Expected per trial, merchants 1–3 EACH: 480 GET / 120 POST'
  echo 'Each trial creates independent application, disposable Postgres and synthetic tokens'
  echo 'No real money, production hosts, or external authentication'
  echo 'Safety: allowlisted 100 RPS, 60 sec, 8 workers, at most 32 outstanding'
  echo 'Results are separate trials: do not average p95 or p99 across trials'
} | tee "$summary"

for trial in 1 2; do
  log="$out/$id-repeat-trial-$trial-100rps-60s.log"
  printf '\nStarting trial %s; detailed evidence: %s\n' "$trial" "$log" | tee -a "$summary"
  if ! mvn -B --no-transfer-progress -f "$root/pom.xml" \
      -Dmixed.lab.rate=100 -Dmixed.lab.duration.seconds=60 \
      -Dtest=MixedHttpWorkloadLab test >"$log" 2>&1; then
    grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 70 | tee -a "$summary" || true
    printf 'STOP: trial %s failed; remaining trials will not run.\n' "$trial" | tee -a "$summary" >&2
    echo "See full error log: $log" >&2
    exit 1
  fi
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" | tee -a "$summary" || true
done
printf '\nCompleted both independent trials. Evidence: %s\n' "$summary"

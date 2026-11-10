#!/usr/bin/env bash
# Explicit opt-in. Local synthetic sustained test, never a production load tool.
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
  echo 'Docker daemon unavailable; start Docker Desktop.' >&2
  exit 2
}
out="$root/target/http-load-lab"
mkdir -p "$out"
id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
summary="$out/$id-sustained-summary.log"
{
  echo 'Educational sustained HTTP test — synthetic, isolated, localhost ONLY'
  echo "UTC: $(date -u +%FT%TZ)"
  echo "Git revision: $(git -C "$root" rev-parse HEAD)"
  echo 'Stage 1: 25 RPS for 30 seconds, 750 measured HTTP requests'
  echo 'Stage 2: 50 RPS for 60 seconds, 3000 measured HTTP requests'
  echo '80% GET, 20% POST, 4 merchants, 8 seeded records each'
  echo '8 workers; 32 outstanding maximum; Hikari maximum 4'
  echo 'Hard cap per test: 6000 requests and 60 seconds, with fail-fast gates'
  echo 'Every stage launches a fresh Spring context and disposable PostgreSQL container'
} | tee "$summary"

trial=0
for spec in '25:30' '50:60'; do
  IFS=: read -r rate duration <<<"$spec"
  trial=$((trial+1))
  log="$out/$id-sustained-$trial-${rate}rps-${duration}s.log"
  printf '\nStage %s: %s RPS x %ss — %s\n' "$trial" "$rate" "$duration" "$log" | tee -a "$summary"
  if ! mvn -B --no-transfer-progress -f "$root/pom.xml" \
      "-Dmixed.lab.rate=$rate" "-Dmixed.lab.duration.seconds=$duration" \
      -Dtest=MixedHttpWorkloadLab test >"$log" 2>&1; then
    grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD FAILURE|ERROR' "$log" | tail -n 50 | tee -a "$summary" || true
    printf 'STOP: stage %s failed. Higher stages not attempted.\n' "$trial" | tee -a "$summary" >&2
    printf 'Diagnostic log: %s\n' "$log" | tee -a "$summary" >&2
    exit 1
  fi
  grep -E 'MIXED_HTTP_LAB|Tests run:|BUILD SUCCESS' "$log" | tee -a "$summary" || true
done
printf '\nCompleted both sustained stages. Evidence summary: %s\n' "$summary"

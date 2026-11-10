#!/usr/bin/env bash
# Explicit opt-in: bounded local HTTP mixed-load experiment with disposable DB.
set -Eeuo pipefail
for required in java mvn git docker; do
  command -v "$required" >/dev/null 2>&1 || {
    printf 'Required command missing: %s\n' "$required" >&2
    exit 2
  }
done
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$script_dir" rev-parse --show-toplevel)"
mkdir -p "$root/target/http-load-lab"
log="$root/target/http-load-lab/$(date -u +%Y%m%dT%H%M%SZ)-mixed-$$.log"
{
  echo "Bounded mixed read/write HTTP lab — synthetic only"
  echo "UTC: $(date -u +%FT%TZ)"
  echo "Revision: $(git -C "$root" rev-parse HEAD)"
  echo "Target: loopback server created by the test; database: disposable PostgreSQL 17"
  echo "Workload: 300 requests, 8 workers, paced at 25 submissions/sec; 80% GET, 20% POST"
  echo "No user-supplied endpoints, no production credentials, no real money"
} | tee "$log"
mvn -B --no-transfer-progress -f "$root/pom.xml" \
  -Dtest=MixedHttpWorkloadLab test 2>&1 | tee -a "$log"
printf 'Mixed-load evidence: %s\n' "$log"

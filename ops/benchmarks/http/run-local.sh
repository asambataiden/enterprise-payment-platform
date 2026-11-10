#!/usr/bin/env bash
# Opt-in real HTTP + PostgreSQL Testcontainers load lab.
# Does NOT target an external URL and does NOT use application credentials.
set -Eeuo pipefail

for command in java mvn git docker; do
  command -v "$command" >/dev/null || { printf 'Missing required command: %s\n' "$command" >&2; exit 2; }
done

root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
mkdir -p "$root/target/http-load-lab"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
result="$root/target/http-load-lab/$run_id.log"

echo "Running isolated signed-JWT HTTP load lab with Testcontainers." | tee "$result"
echo "Revision: $(git -C "$root" rev-parse HEAD)" | tee -a "$result"
echo "No external target, no live merchant credentials, no real payments." | tee -a "$result"

# Explicitly select a class whose name is deliberately excluded from
# ordinary Surefire *Test and Failsafe *IT discovery.
mvn -B --no-transfer-progress \
  -f "$root/pom.xml" \
  -Dtest=ConcurrentHttpLoadLab test 2>&1 | tee -a "$result"

echo "HTTP load lab evidence: $result"

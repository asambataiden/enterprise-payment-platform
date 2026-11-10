#!/usr/bin/env bash
# Isolated, opt-in PostgreSQL 17 synthetic benchmark.
# No host TCP ports; no application DB connection strings or credentials used.
set -Eeuo pipefail

for cmd in docker openssl git; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    printf 'Required tool missing: %s\n' "$cmd" >&2
    exit 2
  fi
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(git -C "$script_dir" rev-parse --show-toplevel)"
sql="$script_dir/statistical.sql"
[[ -f "$sql" ]] || { echo "Missing statistical.sql" >&2; exit 2; }

output_dir="${1:-$root/target/postgresql-benchmark}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-statistical-$"
result="$output_dir/${run_id}.log"
container="epp-pg-statistics-$"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# The temporary database is not reachable through Docker networks or host ports.
# A temporary password satisfies PostgreSQL image initialization but is
# intentionally never echoed or persisted. All DB data is ephemeral.
password="$(openssl rand -hex 24)"
docker run -d --rm --network none \
  --tmpfs /var/lib/postgresql/data:rw,size=1073741824 \
  --name "$container" \
  -e POSTGRES_PASSWORD="$password" \
  postgres:17-alpine >/dev/null
unset password

ready=0
for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready -U postgres -d postgres >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" != "1" ]]; then
  echo "Isolated PostgreSQL lab did not become ready; refusing to run" >&2
  exit 1
fi

{
  echo "Repeated PostgreSQL read/write benchmark"
  echo "UTC timestamp: $(date -u +%FT%TZ)"
  echo "Git revision: $(git -C "$root" rev-parse HEAD)"
  echo "Image: postgres:17-alpine"
  echo "Isolation: --network none; no ports published; ephemeral data"
  echo "Dataset: paired 100000-row synthetic read tables; 12000 write rows per variant"
  echo "NOTE: serial 40-sample read p50/p95 + 12 write batches; not API p95 or concurrency."
  echo
} | tee "$result"

# Pipefail ensures failed SQL stops the run, even while tee writes evidence.
docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 \
  -U postgres -d postgres < "$sql" | tee -a "$result"
echo "Statistical benchmark results: $result"

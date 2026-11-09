#!/usr/bin/env bash
# Provision two lab-only topics and one narrowly scoped Describe ACL.
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -r generated/admin.properties ]]; then
  echo "Generate the temporary certificates first." >&2
  exit 1
fi
compose=(docker compose -f compose.yaml)
broker_exec=("${compose[@]}" exec -T broker)
admin=(/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9093 \
  --command-config /etc/kafka/secrets/admin.properties)
acl=(/opt/kafka/bin/kafka-acls.sh --bootstrap-server localhost:9093 \
  --command-config /etc/kafka/secrets/admin.properties)

# Fail on missing broker readiness rather than provisioning through an insecure listener.
for _ in {1..30}; do
  if "${broker_exec[@]}" "${admin[@]}" --list >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
"${broker_exec[@]}" "${admin[@]}" --list >/dev/null

for topic in payment.security.allowed payment.security.denied; do
  if ! "${broker_exec[@]}" "${admin[@]}" --describe --topic "$topic" >/dev/null 2>&1; then
    "${broker_exec[@]}" "${admin[@]}" --create --topic "$topic" \
      --partitions 1 --replication-factor 1
  fi
done

"${broker_exec[@]}" "${acl[@]}" --add --allow-principal 'User:CN=lab-reader' \
  --operation Describe --topic payment.security.allowed
"${broker_exec[@]}" "${acl[@]}" --list --topic payment.security.allowed
echo "Secured lab ready: reader can Describe the allowed topic only."
echo "Restricted topic must return TopicAuthorizationException in the E2E test."

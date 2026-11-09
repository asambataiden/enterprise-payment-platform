#!/usr/bin/env bash
# Execute external-broker E2E using a throwaway mTLS identity.
set -euo pipefail
cd "$(dirname "$0")"
test -r generated/reader.properties || { echo "Run generate-certificates.sh first" >&2; exit 1; }
export PAYMENT_SECURE_KAFKA_TEST_ENABLED=true
export PAYMENT_SECURE_KAFKA_BOOTSTRAP_SERVERS=localhost:19093
export PAYMENT_SECURE_KAFKA_PROTOCOL=SSL
export PAYMENT_SECURE_KAFKA_ALLOWED_TOPIC=payment.security.allowed
export PAYMENT_SECURE_KAFKA_DENIED_TOPIC=payment.security.denied
export PAYMENT_SECURE_KAFKA_TRUSTSTORE_LOCATION="$PWD/generated/truststore.p12"
export PAYMENT_SECURE_KAFKA_KEYSTORE_LOCATION="$PWD/generated/lab-reader.p12"
export PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD
PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD="$(cat generated/store.password)"
export PAYMENT_SECURE_KAFKA_KEYSTORE_PASSWORD="$PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD"
export PAYMENT_SECURE_KAFKA_KEY_PASSWORD="$PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD"
export PAYMENT_SECURE_KAFKA_ROGUE_TRUSTSTORE_LOCATION="$PWD/generated/untrusted-truststore.p12"
export PAYMENT_SECURE_KAFKA_ROGUE_TRUSTSTORE_PASSWORD="$PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD"
export PAYMENT_SECURE_KAFKA_PRODUCER_KEYSTORE_LOCATION="$PWD/generated/lab-producer.p12"
export PAYMENT_SECURE_KAFKA_CONSUMER_KEYSTORE_LOCATION="$PWD/generated/lab-consumer.p12"
# Run Maven from repository root. No credentials in argv or output.
cd ../../..
exec mvn -B --no-transfer-progress -Dtest=SecuredKafkaBrokerE2E,KafkaNegativeSecurityE2E,KafkaPrincipalAuthorizationE2E test

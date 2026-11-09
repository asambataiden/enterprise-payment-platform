#!/usr/bin/env bash
# Create fresh, throwaway mTLS identities for the isolated Kafka security lab.
# Requires: bash, openssl, Java keytool. NEVER reuse generated credentials.
set -euo pipefail
umask 077
cd "$(dirname "$0")"
OUT="$PWD/generated"
if [[ -e "$OUT" ]]; then
  echo "Refusing to overwrite lab credentials: $OUT" >&2
  exit 1
fi
mkdir -m 700 "$OUT"
trap 'echo "If provisioning failed, remove the incomplete generated directory before retrying." >&2' ERR

openssl rand -hex 24 > "$OUT/store.password"
STORE_PASSWORD="$(cat "$OUT/store.password")"
export STORE_PASSWORD

openssl req -x509 -newkey rsa:3072 -sha256 -nodes \
  -subj "/CN=payment-kafka-lab-ca" -days 2 \
  -keyout "$OUT/ca.key" -out "$OUT/ca.crt" >/dev/null 2>&1

generate_identity() {
  local name="$1" san="$2"
  openssl req -new -newkey rsa:3072 -sha256 -nodes \
    -subj "/CN=$name" \
    -keyout "$OUT/$name.key" -out "$OUT/$name.csr" >/dev/null 2>&1
  cat > "$OUT/$name.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=$san
EOF
  openssl x509 -req -sha256 -days 2 \
    -in "$OUT/$name.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAserial "$OUT/ca.srl" -CAcreateserial \
    -extfile "$OUT/$name.ext" -out "$OUT/$name.crt" >/dev/null 2>&1
  openssl pkcs12 -export -in "$OUT/$name.crt" -inkey "$OUT/$name.key" \
    -certfile "$OUT/ca.crt" -out "$OUT/$name.p12" \
    -passout env:STORE_PASSWORD >/dev/null 2>&1
}
generate_identity "lab-broker" "DNS:broker,DNS:localhost"
generate_identity "lab-admin" "DNS:localhost"
generate_identity "lab-reader" "DNS:localhost"
generate_identity "lab-producer" "DNS:localhost"
generate_identity "lab-consumer" "DNS:localhost"
generate_identity "lab-idempotent-producer" "DNS:localhost"

# Keep the broker keystore name independent of the certificate's principal.
cp "$OUT/lab-broker.p12" "$OUT/broker.p12"
keytool -importcert -noprompt -alias payment-kafka-lab-ca \
  -file "$OUT/ca.crt" -keystore "$OUT/truststore.p12" \
  -storetype PKCS12 -storepass "$STORE_PASSWORD" >/dev/null 2>&1

# Create an unrelated CA truststore to prove the broker certificate is NOT
# accepted when the correct trust anchor is unavailable.
openssl req -x509 -newkey rsa:3072 -sha256 -nodes \
  -subj "/CN=payment-kafka-untrusted-test-ca" -days 2 \
  -keyout "$OUT/untrusted-ca.key" -out "$OUT/untrusted-ca.crt" >/dev/null 2>&1
keytool -importcert -noprompt -alias unrelated-test-ca \
  -file "$OUT/untrusted-ca.crt" -keystore "$OUT/untrusted-truststore.p12" \
  -storetype PKCS12 -storepass "$STORE_PASSWORD" >/dev/null 2>&1

cat > "$OUT/admin.properties" <<EOF
bootstrap.servers=localhost:9093
security.protocol=SSL
ssl.endpoint.identification.algorithm=https
ssl.truststore.location=/etc/kafka/secrets/truststore.p12
ssl.truststore.type=PKCS12
ssl.truststore.password=$STORE_PASSWORD
ssl.keystore.location=/etc/kafka/secrets/lab-admin.p12
ssl.keystore.type=PKCS12
ssl.keystore.password=$STORE_PASSWORD
ssl.key.password=$STORE_PASSWORD
EOF

cat > "$OUT/reader.properties" <<EOF
security.protocol=SSL
ssl.endpoint.identification.algorithm=https
ssl.truststore.location=$OUT/truststore.p12
ssl.truststore.type=PKCS12
ssl.truststore.password=$STORE_PASSWORD
ssl.keystore.location=$OUT/lab-reader.p12
ssl.keystore.type=PKCS12
ssl.keystore.password=$STORE_PASSWORD
ssl.key.password=$STORE_PASSWORD
EOF

# Docker Desktop bind-mounted test secrets must be readable by the Kafka
# image's non-root user. The host-generated lab directory must never be
# shared, backed up or copied to production. Restrict broker to loopback.
chmod 755 "$OUT"
chmod 644 "$OUT/broker.p12" "$OUT/truststore.p12" "$OUT/store.password" \
  "$OUT/admin.properties"
printf 'Temporary local mTLS identities generated. No secrets printed.\n'

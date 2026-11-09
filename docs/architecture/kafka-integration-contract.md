# Milestone 06.6 — Optional Kafka transport and event contract

- **Status:** Opt-in producer and real-broker tests; no automatically started publisher or Kafka consumer
- **Use:** Educational simulated payment events only; no money movement
- **Stack:** Spring Boot 4.1.2, Spring for Apache Kafka 4.1.x, Kafka test broker via Testcontainers
- **PR:** Stacked draft PR #2, based on PR #1

## Boundary

`SimulatedPaymentTransitionService` updates a payment and inserts an outbox record in one PostgreSQL transaction. A manually invoked `PaymentOutboxDispatcher` claims an event; `KafkaPaymentEventPublisher` sends it to a Kafka topic and waits up to eight seconds for broker acknowledgment. Only then does the dispatcher mark the outbox row as dispatched.

Database commit and Kafka delivery are **not** atomic; crashes and lease expiry can produce duplicate messages. Existing retry/backoff/quarantine and inbox deduplication remain necessary. An ACK confirms Kafka acceptance under `acks=all`; it does not prove consumer processing or settlement.

## Contract v1

Each message contains `schemaVersion`, `eventId`, `merchantId`, `paymentId`, and `targetState` as a JSON record. The Kafka message key is `merchantId:paymentId`, preserving per-payment ordering while the partition assignment stays stable. This is **not** a global ordering guarantee across payments, merchants or partition reassignment.

No PAN, personal identification, customer credentials or payment method details are sent. Consumers must reject unknown schema versions and validate trusted event provenance and tenant ownership. The current `TrustedPaymentEventConsumer` verifies against the **local** producer database; it must not be reused as a cross-service trust boundary without an authenticated message contract.

## Activation and security

Kafka is **disabled** by default using `PAYMENT_KAFKA_ENABLED=false`. When enabled, `KafkaPaymentTransportConfiguration` creates a publisher, dispatcher and bounded worker bean, but **no scheduler automatically starts the worker**. It requires an explicitly supplied `KAFKA_BOOTSTRAP_SERVERS` and refuses unencrypted Kafka producer protocols (`PLAINTEXT`, `SASL_PLAINTEXT`) and any producer not configured for `acks=all`.

Configuration supports:
- `PAYMENT_KAFKA_ENABLED=true` to opt into the transport
- `KAFKA_BOOTSTRAP_SERVERS` for broker addresses (required when enabled)
- `PAYMENT_KAFKA_TOPIC` (default `payment.simulated.events.v1`)
- `KAFKA_SECURITY_PROTOCOL` (defaults to `SASL_SSL`; `SSL` is supported)
- Spring Boot `spring.kafka.properties.*` for managed TLS trust and SASL mechanism/credentials; use external secret injection and never commit JAAS secrets

The real-broker Testcontainers tests create a disposable **PLAINTEXT-only local broker** via manually instantiated test factories. This exception is restricted to tests and is not the application activation path. No production Kafka cluster, TLS certificate or credentials have been tested.

The Kafka client uses string serializers, broker `acks=all`, idempotent producer behavior and a bounded publish wait. Bootstrap servers, topic permissions, trust roots, certificate verification and Kafka ACLs must be validated in an actual target environment. The topic must already exist; no automatic topic creation is configured in the Spring application.

## Tests and remaining work

- `KafkaPaymentEventPublisherIT`: publishes versioned events through a real Kafka broker, reads them back as JSON, checks ordering/partition key and rejects invalid topics.
- `KafkaOutboxEndToEndIT`: creates an event in PostgreSQL, dispatches it through a real Kafka broker, then checks the durable outbox ACK and consumed event key.
- Both tests run in CI alongside Postgres Testcontainers, JWT, tenant isolation, ledger and outbox tests.

**Not yet delivered:** Kafka listener/consumer adapter, authenticated cross-service event provenance, schema-registry integration/compatibility testing, a scheduled worker lifecycle, metrics/alerts, remote broker TLS/SASL smoke tests, DLQ replay runbook and chaos testing for crash-after-publish. Keep the PR in draft.

# Senior Java / DevSecOps interview workbook — Enterprise Payment Platform

**Use this as a coaching guide, not a script to memorize.** You should be able to explain every diagram, failure mode and tradeoff without looking at code. The application simulates payment states; do not claim PCI DSS compliance, production Kafka operations or real funds settlement.

## 30-second portfolio introduction

> I built an educational Java 25/Spring Boot payment-intake platform as a modular monolith, starting with a small REST API and PostgreSQL persistence. I incrementally introduced tenant-scoped JWT authorization, concurrency-safe idempotency, a simulated double-entry ledger, atomic state transitions plus a transactional outbox, and an optional Kafka producer. I used Flyway, PostgreSQL/Kafka Testcontainers, ArchUnit and GitHub Actions to demonstrate the invariants. The main focus was correctness under retries and failures, not pretending to process real money.

## Deep-dive path (2–4 minutes)

1. **Problem:** network retries and concurrent requests can duplicate payments, while multiple merchants must not see each other's data.
2. **Boundaries:** JWT verified at HTTP entry, merchant taken from trusted claims; `PaymentService` governs intake, `PaymentStore` abstracts persistence.
3. **Consistency:** DB uniqueness/atomic insert for idempotency; state machine and Postgres row locks for transitions; journal balance checked in Java and DB.
4. **Distribution:** payment + journal + outbox in one DB transaction. Kafka publish later via leased outbox relay, with broker ACK only after send succeeds. At-least-once, not exactly-once.
5. **Evidence:** realistic signed JWTs, PostgreSQL concurrency/rollback integration tests, Kafka Testcontainers producer + E2E test, CI/security scans.
6. **Tradeoffs:** one deployable initially, no real IdP or payment provider, consumer broker authentication and operational SLOs still incomplete.

## Question / answer practice matrix

| Interviewer prompt | Key concepts to explain | Code / test evidence |
| --- | --- | --- |
| Why modular monolith instead of microservices? | cohesive domain, local transactions, lower deployment complexity, extraction when bounded contexts need independent scaling/ownership | ADR-0001, module diagram |
| Why PostgreSQL for idempotency? | composite tenant+key uniqueness; atomic conflict handling across nodes; request term matching | `PaymentService`, `PaymentJdbcWriter`, `PaymentServiceIT` |
| Why `BigDecimal` instead of `double`? | decimal precision; explicit scale policy; currency-specific minor units still future work | `JournalEntry`, `JournalEntryTest` |
| Where does tenant identity come from? | validated JWT, issuer/audience/signature/scope; never caller-selected merchant ID | `PaymentController`, `PaymentBearerTokenIT` |
| Why use `SELECT ... FOR UPDATE`? | serialize competing state transitions for one payment; explain transaction isolation and lock contention | `SimulatedPaymentTransitionServiceIT` |
| Why both Java and database ledger validation? | fail early in domain, prevent bypass via raw SQL, immutable append-only data, commit-time balance | V3, `SimulatedLedgerServiceIT` |
| What does the outbox solve? | atomic DB state and event record; asynchronous message relay without XA/2PC; failure/replay semantics | V4/V5, `PaymentOutboxDispatcherIT` |
| Is Kafka exactly-once? | producer idempotence is not exactly-once across Postgres and Kafka; duplicate after ACK crash, inbox dedup | `KafkaOutboxEndToEndIT`, delivery contract |
| Why `FOR UPDATE SKIP LOCKED` and leases? | parallel claims, bounded retry, stolen lease fencing; potential slow publisher overlap | `PaymentOutboxDispatcherIT` |
| What happens to poison events? | 5-attempt quarantine, no silent deletion; operator-controlled replay needed | V6, dispatcher tests |
| How does consumer deduplication work? | unique (consumer, merchant, event), same transaction as local DB effect; no remote exactly-once claim | `PaymentInbox`, `TrustedPaymentEventConsumer` |
| What does a green security scan guarantee? | only configured scan scope; no production compliance; identity, secrets, deployment and threat model still required | security workflow and PR readiness |
| How is Kafka configured securely? | opt-in, TLS/SASL config guard, broker ACK, provisioning ACLs and secrets outside code; tests use local plaintext only | `KafkaPaymentTransportConfiguration`, Kafka contract |
| How to reduce duplicate classes? | domain ownership, narrow ports/adapters, one contract per rule; extract shared technical libraries only with genuine stable consumers | modularity guide |
| What would you change for production? | threat modeling, authorized event source, refund accounting, IdP integration, TLS/ACL smoke tests, recovery, monitoring, load/chaos tests | roadmap milestones 06.7–09 |

## Guided exercises — solve before reading the corresponding tests

### Exercise 1 — Idempotency race

Two requests for the same merchant and key arrive on separate server nodes. A naive `SELECT` then `INSERT` passes both existence checks. Predict the outcome under a unique index. Draw the timelines and explain how a losing insert finds the winning row and compares terms.

### Exercise 2 — Cross-tenant forgery

Merchant A guesses merchant B's payment UUID. Find **every** boundary where merchant identity must constrain access. Explain why the server must not accept `X-Merchant-Id` as authority and why database FK scope is still valuable.

### Exercise 3 — Commit then publish crash

A capture transaction commits, Kafka accepts the outbox event, and the process crashes before updating `dispatched_at`. Draw the restart path. How can a consumer avoid repeating local DB effects? What still cannot be made exactly-once with arbitrary remote APIs?

### Exercise 4 — Broken journal

A raw SQL caller attempts to create an entry with debit 10.00 and credit 9.99, or insert a third posting after finalization. Which validation triggers fire, when is the transaction rolled back, and why aren't Java constructors enough?

### Exercise 5 — Kafka and ordering

Two transitions for one payment publish with a fixed Kafka key. What order is guaranteed on a stable partition? What can change with retry, outbox concurrency, partition expansion or application-level reordering? Which additional sequence/version fields would improve a consumer's defensive checks?

### Exercise 6 — Architecture review

Challenge the design: Kafka is unused at runtime unless a worker is invoked. Why is this honest and safe for a portfolio? What minimal scheduler/health/metrics/deployment configuration would be required to claim reliable operational publishing?

## How to collect evidence

For each exercise, capture the exact test class, behavior, CI URL, commit SHA and limitation. Do not quote a past test count as if it belongs to a future commit. When adding a new capability, update the roadmap and the appropriate runtime sequence in the **same PR**.

## Resources (concept first, then implementation)

- [C4 model](https://c4model.com/introduction) — context/container/component diagram levels.
- [arc42](https://arc42.org/overview/) — goals, constraints, runtime, ADRs and quality risks.
- [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox) — reliable domain update + message handoff.
- [Idempotent consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html) — duplicate delivery safety.
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html) — bearer authentication.
- [Spring for Apache Kafka](https://docs.spring.io/spring-kafka/reference/) and [Testcontainers Kafka](https://java.testcontainers.org/modules/kafka/) — event delivery and real-broker tests.
- [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/) — security review checklist, not a claim of certification.

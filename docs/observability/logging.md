# Payment Service observability — structured logging

## Decision

Use Spring Boot 4's built-in **Elastic Common Schema (ECS)** console formatter rather than introducing an additional Logback encoder. `application.yml` sets `logging.structured.format.console` to `${LOG_CONSOLE_FORMAT:ecs}`. Supported overrides include `ecs`, `logstash` and `gelf`. Each console record is a JSON object, suitable for ingestion by a log collection agent.

The service's `CorrelationIdFilter` issues a server-controlled UUID in the `X-Correlation-ID` response header and stores it in SLF4J MDC under `correlationId` for the duration of each synchronous request. Spring Boot's ECS formatter includes MDC keys in the JSON log record. This identifier is distinct from W3C `traceparent`, which should be handled by distributed tracing instrumentation as a separate increment.

## Operational and security requirements

- Log stable event names (e.g., `payment.request.rejected`) with structured key-value fields rather than interpolating user input into message strings.
- Permissible context: correlation ID, payment UUID where appropriate, service identifier, status category, elapsed time and error code.
- **Do not log** raw `Idempotency-Key` headers, request/response bodies, tokens, credentials, database passwords, merchant metadata or full personal/payment details.
- Avoid recording unsanitized request URIs or query strings; paths may contain identifiers, and queries can contain secrets.
- Do not log successful payment acceptance from within a database transaction as a durable success: a later transaction commit may still fail.
- Logging must not change HTTP responses or payment behavior. For production, forward stdout to centralized retention-controlled log storage. Configure access controls and retention at the log platform.
- The `correlationId` field is absent from startup/system events outside request handling; this is expected.

## Validation

1. Run `mvn -B clean test` (fast tests, including MVC correlation header and MDC cleanup).
2. Run `mvn -B clean verify` (includes PostgreSQL integration).
3. In a running environment with database configured, make an HTTP request and confirm the `X-Correlation-ID` response header is a UUID.
4. Confirm console records are valid single-line JSON; application request-scope records should have a matching `correlationId` when application code emits log events during the request.
5. Confirm logs contain no raw idempotency keys or payload fields.

## Follow-up

Introduce domain-specific structured events with explicit transactional semantics, Micrometer metrics and W3C trace propagation once those concerns have automated tests. Do not treat correlation IDs alone as distributed tracing or audit events.

# Milestone 06.7K — Jackson Dependency Governance

## Objective

Make JSON serialization and deserialization dependencies
explicit, maintainable and reproducible.

## Problem

The application directly imports Jackson 3 classes,
but jackson-databind was previously available only as
a transitive dependency through Flyway.

A future Flyway dependency change could remove Jackson
from the classpath without any application code change.

## Decision

Declare tools.jackson.core:jackson-databind directly
in pom.xml.

Do not explicitly override the Jackson version.
Use the version managed by Spring Boot 4.1.2.

## Verified dependency graph

enterprise-payment-platform
  -> jackson-databind 3.1.7
     -> jackson-core 3.1.7

## Benefits

- Explicit dependency ownership
- Reduced coupling to Flyway's dependency graph
- Centralized dependency-version management
- Improved maintainability and code review
- Reproducible Maven resolution

## Trade-offs

This change does not introduce a schema registry,
alter JSON serialization, or change event compatibility.

Dependency management and vulnerability monitoring
must still be maintained through CI/CD.

## Verification

Date: 2026-11-09

- pom.xml XML syntax valid
- Maven dependency resolution successful
- 13 focused Kafka contract tests passed
- 65 unit tests passed
- 58 integration tests passed
- 123 total tests passed
- Zero failures, errors or skipped tests

## Interview preparation

Q: Why declare dependencies directly?

A: Application code must not depend on accidental
transitive dependencies from unrelated libraries.

Q: Why omit the version?

A: Spring Boot dependency management provides a
consistent tested dependency version set.

Q: Does a direct declaration improve application security?

A: It improves dependency visibility and governance.
It does not replace vulnerability scanning, patching,
SBOM generation or supply-chain controls.

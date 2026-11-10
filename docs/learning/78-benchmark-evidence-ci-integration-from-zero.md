# 78 — Benchmark Evidence CI Integration From Zero

## 1. What problem are we solving?

A successful local benchmark does not automatically
guarantee that future repository changes preserve
its evidence-validation logic.

We need automated tests that run whenever code is
pushed or a pull request is opened.

## 2. Architecture

The Java CI workflow contains two independent jobs:

- Java verification: Java 25, Maven, PostgreSQL Testcontainers.
- Benchmark evidence: Python contract and integrity tests.

The Security CI workflow retains its existing Trivy
and Gitleaks scanning responsibilities.

The 60-second performance experiment remains opt-in.

## 3. Why separate CI jobs?

Independent jobs provide:

- Clear failure isolation
- Separate toolchain requirements
- Parallel execution
- Simpler maintenance
- More precise status reporting

The Python evidence checks do not require a Java
application server or a running database.

## 4. Local execution

From the repository root:

```bash
python3 ops/benchmarks/http/test_evidence_manifest.py
python3 ops/benchmarks/http/test_independent_process.py
python3 ops/benchmarks/http/test_analyze_correlated_diagnostics.py
```

## 5. Benchmark execution

The existing local runner still performs the bounded,
synthetic 100-RPS, 60-second workload.

After Maven succeeds, it:

1. Validates request-level correlation.
2. Produces the independent analysis log.
3. Creates the versioned evidence manifest.
4. Checks all six artifact hashes.
5. Emits a governance validation marker.

Failures prevent the runner from reporting successful
end-to-end evidence governance.

## 6. Provenance and security

The runner captures the Git commit before execution.

That commit is declared in the manifest.

This is not equivalent to a cryptographically signed
CI attestation and does not prove a clean working tree.

The implementation does not automatically upload raw
Maven, JVM, or HTTP diagnostic logs.

The runner remains limited to synthetic loopback traffic.

## 7. Verification boundaries

Python governance tests verify contracts and failure
handling without executing the benchmark.

The independent workload validates runtime integration
only when explicitly executed.

A passing CI workflow does not certify production
payment performance or production readiness.

## 8. Failure scenarios

- A broken manifest validator fails Python CI.
- A regression in CSV correlation fails Python CI.
- A failed Maven workload stops the local runner.
- Missing benchmark artifacts prevent manifest creation.
- A modified artifact fails SHA-256 verification.
- A missing manifest prevents governance completion.

## 9. Alternatives

A standalone workflow could isolate Python CI further,
but adds an additional workflow to maintain.

Running the entire benchmark on each pull request
would increase cost, duration, and result variability.

External attestation platforms offer stronger provenance,
but are not yet required for this local laboratory.

## 10. Interview preparation

**Why distinguish benchmark execution from CI tests?**

Because contract validation should be fast and
deterministic, whereas performance measurement depends
on runtime environment and workload scheduling.

**Does SHA-256 make evidence tamper-proof?**

No. It detects modifications relative to a trusted hash.
It does not authenticate the artifact's producer.

**Why not publish every performance artifact?**

Raw logs can expose environment metadata or sensitive
information. Publication requires classification and
an explicit retention and access-control policy.

**How would you extend this for enterprise use?**

Add trusted CI provenance, controlled artifact retention,
signed attestations where appropriate, environment
fingerprinting, repeatable baselines, and regression policy.

## 11. Proof and current status

Implementation and local Python test results must be
recorded separately from GitHub Actions execution results.

Do not claim CI success until the remote jobs pass.

Do not claim the revised benchmark runner has passed
until it has been executed or validated using an
appropriate isolated integration test.

## 12. Next milestone

Milestone 26 will introduce multi-run statistical
baselines, comparison reports, and justified regression
thresholds without treating a single local result as
a universal performance target.

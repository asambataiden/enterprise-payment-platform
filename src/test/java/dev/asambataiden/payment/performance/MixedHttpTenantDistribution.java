package dev.asambataiden.payment.performance;

/**
 * Deterministic, synthetic-only load-generator tenant scheduling.
 * Every block of 50 positive sequences covers the 80/20 GET/POST mix
 * and the 70/10/10/10 tenant split independently for each operation.
 */
final class MixedHttpTenantDistribution {
    private MixedHttpTenantDistribution() {}

    static int merchantIndex(int sequence) {
        if (sequence == 0 || sequence == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Sequence must be a non-zero bounded value");
        }
        int n = Math.abs(sequence);
        int bucket = ((n / 5) * 7 + (n % 5) * 3) % 10;
        return bucket < 7 ? 0 : bucket - 6;
    }
}

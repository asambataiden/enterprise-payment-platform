package dev.asambataiden.payment.payments.outbox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded supervised polling cycle. Caller controls scheduling and lifecycle.
 * No transport is autowired or launched by default.
 */
public final class SupervisedPaymentOutboxWorker {
    private final PaymentOutboxDispatcher dispatcher;
    private final int maxClaimsPerCycle;
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong failedCycles = new AtomicLong();

    public SupervisedPaymentOutboxWorker(PaymentOutboxDispatcher dispatcher, int maxClaimsPerCycle) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        if (maxClaimsPerCycle < 1 || maxClaimsPerCycle > 100) {
            throw new IllegalArgumentException("Batch size must be 1 through 100");
        }
        this.maxClaimsPerCycle = maxClaimsPerCycle;
    }

    /** Limit work per cycle; exceptions propagate to the supervising caller. */
    public int runCycle() {
        int processed = 0;
        try {
            while (processed < maxClaimsPerCycle && dispatcher.dispatchOne()) {
                processed++;
                claimed.incrementAndGet();
            }
        } catch (RuntimeException failure) {
            failedCycles.incrementAndGet();
            throw failure;
        }
        return processed;
    }

    public long claimedCount() { return claimed.get(); }
    public long failedCycleCount() { return failedCycles.get(); }
}

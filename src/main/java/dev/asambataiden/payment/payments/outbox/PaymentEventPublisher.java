package dev.asambataiden.payment.payments.outbox;

/**
 * External/broker transport boundary. Implementations must not claim exactly-once
 * remote delivery. Publish may succeed before its caller can record an ACK.
 */
@FunctionalInterface
public interface PaymentEventPublisher {
    void publish(PaymentOutboxEvent event) throws Exception;
}

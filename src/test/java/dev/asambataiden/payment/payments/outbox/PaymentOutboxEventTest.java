package dev.asambataiden.payment.payments.outbox;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentOutboxEventTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    @Test
    void acceptsMerchantAtMaximumLength() {
        var event = create("m".repeat(128));

        assertEquals(128, event.merchantId().length());
        assertEquals(1, event.schemaVersion());
    }

    @Test
    void rejectsMerchantAboveMaximumLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> create("m".repeat(129))
        );
    }

    @Test
    void rejectsBlankOrMissingMerchant() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> create(null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> create("")),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> create("   "))
        );
    }

    @Test
    void rejectsUnsupportedEventVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentOutboxEvent(
                        2, EVENT_ID, "merchant-1",
                        PAYMENT_ID, "AUTHORIZED")
        );
    }

    private static PaymentOutboxEvent create(String merchantId) {
        return new PaymentOutboxEvent(
                1, EVENT_ID, merchantId,
                PAYMENT_ID, "AUTHORIZED"
        );
    }
}

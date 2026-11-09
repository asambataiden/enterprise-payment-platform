package dev.asambataiden.payment.payments.messaging.kafka.contract;

import dev.asambataiden.payment.payments.messaging.kafka.consumer.LocalKafkaPaymentRecordProcessor;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;
import dev.asambataiden.payment.payments.outbox.TrustedPaymentEventConsumer;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaConsumerContractTest {

    private static final String TOPIC =
            "payment.simulated.events.v1";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void validV1EventReachesTrustedConsumer() throws Exception {
        var trusted = mock(TrustedPaymentEventConsumer.class);
        var processor = new LocalKafkaPaymentRecordProcessor(
                TOPIC, trusted);

        var event = event();

        when(trusted.consume(
                eq("reporting"),
                any(PaymentOutboxEvent.class),
                any(Runnable.class)
        )).thenReturn(true);

        boolean accepted = processor.process(
                "reporting",
                record(event),
                () -> {}
        );

        assertTrue(accepted);

        var captured = ArgumentCaptor.forClass(
                PaymentOutboxEvent.class);

        verify(trusted).consume(
                eq("reporting"),
                captured.capture(),
                any(Runnable.class)
        );

        assertEquals(event, captured.getValue());
    }

    @Test
    void invalidVersionIsRejectedBeforeDatabaseBoundary()
            throws Exception {
        var event = event();
        String payload = json.writeValueAsString(event)
                .replace("\"schemaVersion\":1",
                         "\"schemaVersion\":2");

        assertRejected(payload, key(event));
    }

    @Test
    void unknownAndMissingFieldsAreRejected()
            throws Exception {
        var event = event();
        String payload = json.writeValueAsString(event);

        assertRejected(
                payload.replaceFirst(
                        "\\}$", ",\"unexpected\":true}"),
                key(event)
        );

        assertRejected(
                payload.replaceFirst(
                        "\"targetState\":\"AUTHORIZED\",?", "")
                        .replace(",}", "}"),
                key(event)
        );
    }

    @Test
    void mismatchedMerchantPartitionKeyIsRejected()
            throws Exception {
        var event = event();

        assertRejected(
                json.writeValueAsString(event),
                "different-merchant:" + event.paymentId()
        );
    }

    @Test
    void invalidIdentifiersAndStatesAreRejected()
            throws Exception {
        var event = event();
        String payload = json.writeValueAsString(event);

        assertRejected(
                payload.replace(
                        event.eventId().toString(),
                        "not-a-uuid"),
                key(event)
        );

        assertRejected(
                payload.replace(
                        "AUTHORIZED", "UNKNOWN"),
                key(event)
        );
    }


    @Test
    void rejectsDuplicateMerchantIdentifiers() throws Exception {
        var event = event();
        String payload = json.writeValueAsString(event);

        String duplicated = payload.replace(
                "\"merchantId\":\"merchant-contract-test\"",
                "\"merchantId\":\"merchant-contract-test\","
                + "\"merchantId\":\"merchant-contract-test\""
        );

        assertNotEquals(payload, duplicated);
        assertRejected(duplicated, key(event));
    }

    @Test
    void rejectsNullAndIncorrectFieldTypes() throws Exception {
        var event = event();
        String payload = json.writeValueAsString(event);

        String nullMerchant = payload.replace(
                "\"merchantId\":\"merchant-contract-test\"",
                "\"merchantId\":null"
        );

        String stringVersion = payload.replace(
                "\"schemaVersion\":1",
                "\"schemaVersion\":\"1\""
        );

        assertNotEquals(payload, nullMerchant);
        assertNotEquals(payload, stringVersion);

        assertRejected(nullMerchant, key(event));
        assertRejected(stringVersion, key(event));
    }

    @Test
    void rejectsOversizedUtf8PayloadBeforeTrustedConsumer() {
        assertRejected("€".repeat(1366), "merchant:payment");
    }

    private void assertRejected(
            String payload,
            String recordKey
    ) {
        var trusted = mock(TrustedPaymentEventConsumer.class);
        var processor = new LocalKafkaPaymentRecordProcessor(
                TOPIC, trusted);

        var record = new ConsumerRecord<>(
                TOPIC, 0, 0L, recordKey, payload);

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.process(
                        "reporting", record,
                        () -> fail("Invalid event executed effect"))
        );

        verifyNoInteractions(trusted);
    }

    private ConsumerRecord<String, String> record(
            PaymentOutboxEvent event
    ) throws Exception {
        return new ConsumerRecord<>(
                TOPIC,
                0,
                0L,
                key(event),
                json.writeValueAsString(event)
        );
    }

    private static String key(PaymentOutboxEvent event) {
        return event.merchantId() + ":" + event.paymentId();
    }

    private static PaymentOutboxEvent event() {
        return new PaymentOutboxEvent(
                1,
                UUID.randomUUID(),
                "merchant-contract-test",
                UUID.randomUUID(),
                "AUTHORIZED"
        );
    }
}

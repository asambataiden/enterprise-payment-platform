package dev.asambataiden.payment.payments.messaging.kafka.contract;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the reviewed JSON Schema 2020-12 with a standards-aware validator.
 * This is separate from the Kafka consumer's additional trust/ownership checks.
 */
class PaymentEventJsonSchemaValidationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Schema SCHEMA = loadSchema();

    @Test
    void acceptsPublishedCanonicalEvent() throws Exception {
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                1, UUID.randomUUID(), "merchant-1",
                UUID.randomUUID(), "AUTHORIZED");

        assertTrue(valid(JSON.writeValueAsString(event)));
    }

    @Test
    void rejectsWrongTypesMissingFieldsAndUnknownAttributes() {
        String eventId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        String valid = payload(eventId, paymentId, "merchant-1");

        assertFalse(valid(valid.replace("\"schemaVersion\":1",
                "\"schemaVersion\":\"1\"")));
        assertFalse(valid(valid.replace(
                ",\"targetState\":\"AUTHORIZED\"", "")));
        assertFalse(valid(valid.replace("}", ",\"extra\":true}")));
    }

    @Test
    void rejectsNonCanonicalUuidTextAndOverlongAsciiMerchant() {
        String eventId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();

        assertFalse(valid(payload("1-1-1-1-1", paymentId, "merchant-1")));
        assertFalse(valid(payload(eventId, paymentId, "x".repeat(129))));
    }

    @Test
    void characterizesUnicodeSchemaVsJavaPolicyWithoutChangingProduction() {
        String merchant = "🚀".repeat(65);
        assertEquals(65, merchant.codePointCount(0, merchant.length()));
        assertEquals(130, merchant.length());

        assertTrue(valid(payload(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                merchant)));

        assertThrows(IllegalArgumentException.class, () ->
                new PaymentOutboxEvent(1, UUID.randomUUID(), merchant,
                        UUID.randomUUID(), "AUTHORIZED"));
    }

    private static boolean valid(String payload) {
        return SCHEMA.validate(payload, InputFormat.JSON,
                executionContext -> executionContext.executionConfig(
                        config -> config.formatAssertionsEnabled(true))).isEmpty();
    }

    private static String payload(String eventId, String paymentId,
                                  String merchantId) {
        try {
            return JSON.writeValueAsString(java.util.Map.of(
                    "schemaVersion", 1,
                    "eventId", eventId,
                    "paymentId", paymentId,
                    "merchantId", merchantId,
                    "targetState", "AUTHORIZED"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema loadSchema() {
        try (InputStream input = PaymentEventJsonSchemaValidationTest.class
                .getResourceAsStream("/contracts/payment-event-v1.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing reviewed payment event schema");
            }
            String schemaText =
                    new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaText, InputFormat.JSON);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load payment event schema", e);
        }
    }
}

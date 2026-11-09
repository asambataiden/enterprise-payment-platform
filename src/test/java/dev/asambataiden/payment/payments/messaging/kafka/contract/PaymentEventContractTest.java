package dev.asambataiden.payment.payments.messaging.kafka.contract;

import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies selected v1 wire-contract invariants against the
 * reviewed JSON Schema.
 *
 * This is a focused contract test, not a general JSON Schema
 * validation engine.
 */
class PaymentEventContractTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static final String SCHEMA_RESOURCE =
            "/contracts/payment-event-v1.schema.json";

    private static final Set<String> V1_FIELDS = Set.of(
            "schemaVersion",
            "eventId",
            "merchantId",
            "paymentId",
            "targetState"
    );

    @Test
    void reviewedSchemaMatchesSupportedV1WireFields()
            throws Exception {

        Map<String, Object> schema = schema();

        assertEquals(
                "https://json-schema.org/draft/2020-12/schema",
                schema.get("$schema")
        );

        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE,
                schema.get("additionalProperties"));

        assertEquals(
                V1_FIELDS,
                Set.copyOf(list(schema.get("required")))
        );

        assertEquals(
                V1_FIELDS,
                object(schema.get("properties")).keySet()
        );
    }

    @Test
    void serializedDomainEventConformsToV1Contract()
            throws Exception {

        PaymentOutboxEvent event = new PaymentOutboxEvent(
                1,
                UUID.randomUUID(),
                "merchant-contract-test",
                UUID.randomUUID(),
                "AUTHORIZED"
        );

        String payload = JSON.writeValueAsString(event);

        Map<String, Object> decoded = decode(payload);

        assertTrue(isValidV1(decoded));
        assertEquals(V1_FIELDS, decoded.keySet());

        PaymentOutboxEvent restored =
                JSON.readValue(payload, PaymentOutboxEvent.class);

        assertEquals(event, restored);
    }

    @Test
    void rejectsMissingAndUnknownProperties() throws Exception {
        Map<String, Object> valid = validPayload();

        Map<String, Object> missing = new HashMap<>(valid);
        missing.remove("paymentId");

        assertFalse(isValidV1(missing));

        Map<String, Object> unknown = new HashMap<>(valid);
        unknown.put("unexpected", true);

        assertFalse(isValidV1(unknown));
    }

    @Test
    void rejectsUnsupportedVersionAndInvalidIdentifiers()
            throws Exception {

        Map<String, Object> invalidVersion = validPayload();
        invalidVersion.put("schemaVersion", 2);

        assertFalse(isValidV1(invalidVersion));

        Map<String, Object> invalidEventId = validPayload();
        invalidEventId.put("eventId", "not-a-uuid");

        assertFalse(isValidV1(invalidEventId));

        Map<String, Object> invalidPaymentId = validPayload();
        invalidPaymentId.put("paymentId", "not-a-uuid");

        assertFalse(isValidV1(invalidPaymentId));
    }

    @Test
    void rejectsInvalidMerchantAndUnsupportedState()
            throws Exception {

        Map<String, Object> blankMerchant = validPayload();
        blankMerchant.put("merchantId", "");

        assertFalse(isValidV1(blankMerchant));

        Map<String, Object> longMerchant = validPayload();
        longMerchant.put("merchantId", "x".repeat(129));

        assertFalse(isValidV1(longMerchant));

        Map<String, Object> invalidState = validPayload();
        invalidState.put("targetState", "UNKNOWN");

        assertFalse(isValidV1(invalidState));
    }

    private static boolean isValidV1(
            Map<String, Object> payload
    ) throws Exception {

        Map<String, Object> schema = schema();
        Map<String, Object> properties =
                object(schema.get("properties"));

        Set<String> required =
                Set.copyOf(list(schema.get("required")));

        if (!payload.keySet().containsAll(required)) {
            return false;
        }

        if (Boolean.FALSE.equals(
                schema.get("additionalProperties"))
                && !properties.keySet().containsAll(
                        payload.keySet())) {
            return false;
        }

        Map<String, Object> version =
                object(properties.get("schemaVersion"));

        if (!version.get("const").equals(
                payload.get("schemaVersion"))) {
            return false;
        }

        if (!validUuid(payload.get("eventId"))
                || !validUuid(payload.get("paymentId"))) {
            return false;
        }

        Object merchantValue = payload.get("merchantId");

        if (!(merchantValue instanceof String merchant)) {
            return false;
        }

        Map<String, Object> merchantSchema =
                object(properties.get("merchantId"));

        int min = ((Number) merchantSchema.get(
                "minLength")).intValue();

        int max = ((Number) merchantSchema.get(
                "maxLength")).intValue();

        int length = merchant.codePointCount(
                0, merchant.length());

        if (length < min || length > max) {
            return false;
        }

        Map<String, Object> stateSchema =
                object(properties.get("targetState"));

        return list(stateSchema.get("enum"))
                .contains(payload.get("targetState"));
    }

    private static boolean validUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }

        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = new HashMap<>();

        payload.put("schemaVersion", 1);
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("merchantId", "merchant-contract-test");
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("targetState", "AUTHORIZED");

        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema()
            throws Exception {

        try (InputStream input =
                     PaymentEventContractTest.class
                             .getResourceAsStream(SCHEMA_RESOURCE)) {

            assertNotNull(input,
                    "Reviewed v1 event schema is missing");

            return JSON.readValue(input, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String json)
            throws Exception {

        return JSON.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        assertInstanceOf(List.class, value);
        return (List<String>) value;
    }
}

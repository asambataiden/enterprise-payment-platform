package dev.asambataiden.payment.shared.observability;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesCorrelationIdAndRemovesMdcAfterRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/payments");
        var response = new MockHttpServletResponse();
        var observedInChain = new AtomicReference<String>();
        MDC.remove(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, (req, res) ->
                observedInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        String header = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(header);
        assertDoesNotThrow(() -> UUID.fromString(header));
        assertEquals(header, observedInChain.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void ignoresUntrustedIncomingIdAndRestoresPriorMdc() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/payments");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "attacker-supplied-id");
        var response = new MockHttpServletResponse();

        MDC.put(CorrelationIdFilter.MDC_KEY, "existing-context");
        try {
            filter.doFilter(request, response, (req, res) ->
                    assertNotEquals("attacker-supplied-id",
                            MDC.get(CorrelationIdFilter.MDC_KEY)));

            assertNotEquals("attacker-supplied-id",
                    response.getHeader(CorrelationIdFilter.HEADER_NAME));
            assertEquals("existing-context", MDC.get(CorrelationIdFilter.MDC_KEY));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}

package dev.asambataiden.payment.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates an untrusted-client-independent identifier for each HTTP request.
 * Prefer platform-managed W3C trace context for distributed tracing; this ID
 * is a separate local troubleshooting aid.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        response.setHeader(HEADER_NAME, correlationId);

        // Always restore the prior MDC value to avoid leaking context
        // between requests handled by reused container threads.
        String previous = MDC.get(MDC_KEY);
        try {
            MDC.put(MDC_KEY, correlationId);
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}

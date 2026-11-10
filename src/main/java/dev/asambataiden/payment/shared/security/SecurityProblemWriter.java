package dev.asambataiden.payment.shared.security;

import dev.asambataiden.payment.shared.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes sanitized RFC 9457 responses for security-filter failures, which
 * occur before MVC @RestControllerAdvice can handle the request.
 * Authentication entry points retain standard WWW-Authenticate headers.
 */
final class SecurityProblemWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SecurityProblemWriter() {}

    static void write(HttpServletResponse response, int status, String title,
                      String code, String slug, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:problem:enterprise-payment-platform:" + slug);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.put("correlationId", correlationId);
        }
        response.getWriter().write(JSON.writeValueAsString(problem));
    }
}

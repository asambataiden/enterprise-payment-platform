package dev.asambataiden.payment.payments.api.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents only the externally supported simulated payment HTTP API.
 * Documentation endpoints are disabled by default and require payments:docs.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentOpenApiConfiguration {
    @Bean
    OpenAPI simulatedPaymentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Payment Platform — Simulated Payment API")
                        .version("v1")
                        .description("Educational payment intake only. No real funds movement, "
                                + "cardholder data, refunds or public state-transition API."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}

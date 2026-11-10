package dev.asambataiden.payment.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token boundary. JWT verification is configured through
 * Spring Boot's resource-server issuer and audience properties.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class PaymentSecurityConfig {

    @Bean
    SecurityFilterChain paymentSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // Stateless bearer-token API; no cookie session.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                            .hasAuthority("SCOPE_payments:metrics")
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                            .hasAuthority("SCOPE_payments:docs")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/simulated-transitions")
                            .hasAuthority("SCOPE_payments:simulate")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                            .hasAuthority("SCOPE_payments:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/*")
                            .hasAuthority("SCOPE_payments:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}

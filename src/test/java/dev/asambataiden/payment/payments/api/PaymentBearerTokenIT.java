package dev.asambataiden.payment.payments.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full Spring Security filter chain + PaymentController + PostgreSQL.
 * These requests carry actual signed bearer tokens, not jwt() mock principals.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentBearerTokenIT.TrustedTestIssuer.class)
class PaymentBearerTokenIT {
    private static final String ISSUER = "https://trusted.test.invalid/realms/payments";
    private static final String AUDIENCE = "enterprise-payment-service";
    private static final KeyPair TRUSTED_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TrustedTestIssuer {
        @Bean
        JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED_KEY.getPublic()).build();
            OAuth2TokenValidator<Jwt> audience = jwt ->
                    jwt.getAudience().contains(AUDIENCE)
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error("invalid_token", "Wrong audience", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create ephemeral test signing key", e);
        }
    }

    private static String token(KeyPair key, String issuer, String audience,
                                Instant expiration, String merchant, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("merchant-client")
                .claim("merchant_id", merchant)
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(expiration))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static String trusted(String merchant, String scope) throws Exception {
        return token(TRUSTED_KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
                merchant, scope);
    }

    @Test
    void validSignedJwtCreatesAndRetrievesPaymentForOwningMerchant() throws Exception {
        String key = "signed-create-" + UUID.randomUUID();
        String token = trusted("merchant-a", "payments:write payments:read");
        var result = mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":27.50,"currency":"EUR","merchantReference":"signed-order"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        org.junit.jupiter.api.Assertions.assertNotNull(location);
        mvc.perform(get(location).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantReference").value("signed-order"));
    }

    @Test
    void anotherMerchantWithCorrectScopeCannotReadPayment() throws Exception {
        String key = "cross-merchant-http-" + UUID.randomUUID();
        String owner = trusted("merchant-a", "payments:write payments:read");
        var created = mvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"owner-only"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        org.junit.jupiter.api.Assertions.assertNotNull(location);
        mvc.perform(get(location)
                        .header("Authorization", "Bearer " + trusted("merchant-b", "payments:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " +
                                token(keyPair(), ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
                                        "merchant-a", "payments:read")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongIssuerAndAudience() throws Exception {
        for (String[] invalid : new String[][] {
                {"https://untrusted.test.invalid", AUDIENCE},
                {ISSUER, "another-service"}
        }) {
            mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                            .header("Authorization", "Bearer " +
                                    token(TRUSTED_KEY, invalid[0], invalid[1],
                                            Instant.now().plusSeconds(300), "merchant-a", "payments:read")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsExpiredBearerToken() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " +
                                token(TRUSTED_KEY, ISSUER, AUDIENCE, Instant.now().minusSeconds(3600),
                                        "merchant-a", "payments:read")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInsufficientScopeAndMissingMerchantClaim() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + trusted("merchant-a", "payments:write")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + trusted("", "payments:read")))
                .andExpect(status().isForbidden());
    }
}

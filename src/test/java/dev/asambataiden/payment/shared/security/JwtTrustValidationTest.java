package dev.asambataiden.payment.shared.security;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signed JWT validation contract: independent of MockMvc jwt() test principal
 * shortcuts. Ephemeral keys are generated per test run; none are committed.
 */
class JwtTrustValidationTest {
    private static final String ISSUER = "https://issuer.test.invalid/payments";
    private static final String AUDIENCE = "enterprise-payment-service";
    private static KeyPair signingKey;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
                jwt.getAudience().contains(AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Untrusted audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audienceValidator));
    }

    private static String signedToken(KeyPair key, String issuer, String audience, Instant expiresAt)
            throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("merchant-service-account")
                .claim("merchant_id", "merchant-a")
                .claim("scope", "payments:read payments:write")
                .issueTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(expiresAt))
                .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return token.serialize();
    }

    @Test
    void acceptsValidSignatureIssuerAudienceAndUnmodifiedMerchantClaim() throws Exception {
        Jwt jwt = decoder.decode(signedToken(signingKey, ISSUER, AUDIENCE,
                Instant.now().plusSeconds(300)));
        assertEquals("merchant-a", jwt.getClaimAsString("merchant_id"));
        assertEquals(ISSUER, jwt.getIssuer().toString());
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        assertThrows(JwtException.class, () -> decoder.decode(signedToken(
                signingKey, "https://attacker.test.invalid", AUDIENCE,
                Instant.now().plusSeconds(300))));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        assertThrows(JwtException.class, () -> decoder.decode(signedToken(
                signingKey, ISSUER, "unrelated-service", Instant.now().plusSeconds(300))));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertThrows(JwtException.class, () -> decoder.decode(signedToken(
                signingKey, ISSUER, AUDIENCE, Instant.now().minusSeconds(3600))));
    }

    @Test
    void rejectsTokenSignedByUntrustedKeyEvenWithCorrectClaims() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attackerKey = generator.generateKeyPair();
        assertThrows(JwtException.class, () -> decoder.decode(signedToken(
                attackerKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300))));
    }
}

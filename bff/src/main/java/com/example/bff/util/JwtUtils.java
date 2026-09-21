package com.example.bff.util;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and verifies the signed JWT stored in the BFF_SESSION cookie.
 * <p>
 * The RSA signing/verification key pair is resolved exactly once, at startup (see
 * {@link #initKeys()}), instead of being re-parsed from the PEM property on every request as
 * before - both because that parsing is pure overhead on every single BFF_SESSION-bearing
 * request, and because it lets us fail fast (or fall back to an ephemeral key) a single time at
 * boot rather than on the first request that happens to hit it.
 */
@Component
@Slf4j
public class JwtUtils {

    private final Environment env;

    @Value("${bff.jwt.signing-key:}")
    private String signingKey;

    @Value("${bff.jwt.issuer}")
    private String issuer;

    @Value("${bff.session.ttl-minutes}")
    private int sessionTtlMinutes;

    private RSASSASigner signer;
    private RSASSAVerifier verifier;

    public JwtUtils(Environment env) {
        this.env = env;
    }

    /**
     * Resolves the signing key once at startup and caches the derived {@link RSASSASigner}/
     * {@link RSASSAVerifier}.
     * <p>
     * When {@code bff.jwt.signing-key} (backed by {@code BFF_JWT_SIGNING_KEY}) is blank:
     * <ul>
     *   <li>with the {@code prod} profile active, startup fails fast with an
     *       {@link IllegalStateException} - shipping to production with no configured signing
     *       key is a misconfiguration, not something to silently paper over;</li>
     *   <li>otherwise, an ephemeral 2048-bit RSA key pair is generated in memory for the life of
     *       this process. This keeps local/dev/test environments working out of the box (no
     *       baked-in key that everyone who ever cloned this template also has), at the cost that
     *       every BFF_SESSION issued before a restart is rejected afterwards - acceptable for
     *       dev, never for prod.</li>
     * </ul>
     */
    @PostConstruct
    void initKeys() {
        try {
            PrivateKey privateKey;
            if (signingKey == null || signingKey.isBlank()) {
                if (env.acceptsProfiles(Profiles.of("prod"))) {
                    throw new IllegalStateException(
                            "BFF_JWT_SIGNING_KEY is not set. The 'prod' profile requires a "
                                    + "configured RSA private key for signing BFF_SESSION JWTs; "
                                    + "refusing to start with none configured.");
                }
                log.warn("BFF_JWT_SIGNING_KEY not set - using an ephemeral key; sessions will not survive a restart");
                privateKey = generateEphemeralPrivateKey();
            } else {
                privateKey = loadPrivateKey(signingKey);
            }

            RSAPublicKey publicKey = derivePublicKey((RSAPrivateCrtKey) privateKey);
            this.signer = new RSASSASigner(privateKey);
            this.verifier = new RSASSAVerifier(publicKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize the BFF session signing key", e);
        }
    }

    public String issueSessionJwt(String jti, OAuth2AuthenticationToken auth) {
        try {
            OidcUser user = (OidcUser) auth.getPrincipal();

            Date now = new Date();
            Date exp = new Date(now.getTime() + (long) sessionTtlMinutes * 60 * 1000);

            assert user != null;
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(user.getName())
                    .jwtID(jti)
                    .issueTime(now)
                    .expirationTime(exp)
                    .claim("email", user.getEmail())
                    .claim("name", user.getFullName())
                    .build();

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.RS256),
                    claims);

            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to issue JWT", e);
        }
    }

    public String extractJti(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            // 1. Verify Signature
            if (!signedJWT.verify(verifier)) {
                return null; // Invalid signature
            }

            // 2. Validate Expiration
            Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (expirationTime == null || new Date().after(expirationTime)) {
                return null; // Expired
            }

            // 3. Validate Issuer
            String claimIssuer = signedJWT.getJWTClaimsSet().getIssuer();
            if (!issuer.equals(claimIssuer)) {
                return null; // Invalid Issuer
            }

            return signedJWT.getJWTClaimsSet().getJWTID();
        } catch (Exception e) {
            return null;
        }
    }

    private PrivateKey loadPrivateKey(String key) throws GeneralSecurityException {
        String privateKeyPEM = key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private PrivateKey generateEphemeralPrivateKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return keyPair.getPrivate();
    }

    private RSAPublicKey derivePublicKey(RSAPrivateCrtKey privateKey) throws GeneralSecurityException {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
    }
}

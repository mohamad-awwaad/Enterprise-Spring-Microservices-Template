package com.example.bff.session;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable snapshot of everything the BFF needs to act on behalf of a logged-in user,
 * persisted in Redis under the {@code bff:session:<jti>} key (see {@code SessionRedisService}).
 * <p>
 * This replaces storing the framework's {@code OAuth2AuthorizedClient} (via JDK
 * serialization) directly in Redis:
 * <ul>
 *   <li><b>No client secret in Redis.</b> {@code OAuth2AuthorizedClient} embeds the whole
 *       {@code ClientRegistration}, including {@code client-secret}. A Redis compromise (or an
 *       overly broad Redis ACL/backup) would hand over that secret too. This record only ever
 *       holds the tokens issued to a specific user, never the registration that produced them.</li>
 *   <li><b>No Java deserialization surface.</b> JDK deserialization of a value an attacker can
 *       influence (e.g. via a Redis compromise) is a classic RCE gadget-chain vector. Storing a
 *       plain JSON record instead of a {@code Serializable} object closes that off entirely.</li>
 *   <li><b>Survives Spring Security upgrades.</b> {@code OAuth2AuthorizedClient}'s internal shape
 *       is not a stable serialization contract across versions; a routine Spring Security bump
 *       could silently break every session already sitting in Redis. This record's shape is ours
 *       to own and version.</li>
 * </ul>
 *
 * @param principalName         the OAuth2 principal name (subject) this session belongs to
 * @param accessToken           the current access token value forwarded to downstream services
 * @param accessTokenExpiresAt  when {@code accessToken} expires, or {@code null} if unknown
 * @param refreshToken          the refresh token value, or {@code null} if none was issued
 * @param idToken               the OIDC ID token value, used for Keycloak's RP-initiated logout
 * @param scopes                the scopes granted to the access token
 */
public record BffSession(
        String principalName,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        String idToken,
        Set<String> scopes) {
}

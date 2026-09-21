package com.example.bff.filter;

import com.example.bff.service.RefreshLockService;
import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * <p><strong>Proactive Token Refresh Filter</strong></p>
 *
 * <p>This filter implements the "Defense in Depth" strategy by ensuring that
 * tokens forwarded to downstream microservices have a sufficient lifespan ("Safe Buffer").</p>
 *
 * <p><strong>Why is this necessary?</strong><br>
 * Standard OAuth2 clients refresh tokens only when they are strictly expired.
 * In a distributed system, a token might be valid at the Gateway but expire
 * milliseconds later when it reaches the Order Service ("Mid-Flight Expiration").
 * This filter proactively refreshes tokens that are <em>about to expire</em>
 * (configurable via {@code bff.token.refresh-buffer-seconds}).</p>
 *
 * <p><strong>Why Manual Implementation?</strong><br>
 * 1. <strong>Custom Session:</strong> The architecture uses a custom {@code BFF_SESSION} cookie
 *    and explicit Redis storage, bypassing standard {@code JSESSIONID} mechanisms.<br>
 * 2. <strong>Stability:</strong> Spring Security's internal {@code TokenResponseClient} implementations
 *    change frequently between versions. A manual {@code RestClient} implementation ensures
 *    stability and full control over the refresh logic.</p>
 */
@Component
@Slf4j
public class TokenRefreshFilter extends OncePerRequestFilter {

    /**
     * The BffController proxy endpoints are mapped at "/bff" + "/api/**", so the real
     * request URI is "/bff/api/...", not "/api/...". Using the wrong prefix here means
     * this filter silently never runs on proxied requests.
     */
    private static final String API_PATH_PREFIX = "/bff/api/";

    /**
     * The registration id used for the Keycloak OAuth2 client, as configured under
     * {@code spring.security.oauth2.client.registration.keycloak.*} in application.properties.
     * Sessions no longer carry their own {@code ClientRegistration} (see {@link BffSession}), so
     * this filter looks the client id/secret/token URI up from the shared repository instead.
     */
    private static final String KEYCLOAK_REGISTRATION_ID = "keycloak";

    private final long refreshBufferSeconds;
    private final SessionRedisService sessionService;
    private final JwtUtils jwtUtils;
    private final RestClient restClient;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final RefreshLockService refreshLockService;

    public TokenRefreshFilter(SessionRedisService sessionService,
                              JwtUtils jwtUtils,
                              RestClient.Builder restClientBuilder,
                              ClientRegistrationRepository clientRegistrationRepository,
                              @org.springframework.beans.factory.annotation.Value("${bff.token.refresh-buffer-seconds}") long refreshBufferSeconds,
                              RefreshLockService refreshLockService) {
        this.sessionService = sessionService;
        this.jwtUtils = jwtUtils;
        // Using Builder allows us to inject a mock/custom builder in tests
        this.restClient = restClientBuilder.build();
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.refreshBufferSeconds = refreshBufferSeconds;
        this.refreshLockService = refreshLockService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // Only apply to API requests where we act as a proxy
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Extract Custom BFF_SESSION Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String sessionJwt = Arrays.stream(cookies)
                .filter(c -> "BFF_SESSION".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (sessionJwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Validate Session JWT and Extract JTI (Redis Key)
        String jti = jwtUtils.extractJti(sessionJwt);
        if (jti == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Load BFF Session from Redis
        BffSession session = sessionService.load(jti);
        if (session == null || session.refreshToken() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Check for Proactive Refresh Condition
        Instant accessTokenExpiresAt = session.accessTokenExpiresAt();
        if (accessTokenExpiresAt != null) {
            long secondsRemaining = accessTokenExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();

            if (secondsRemaining < refreshBufferSeconds) {
                // 5. Race-safe refresh: only one request (across every BFF instance) may ever
                // actually call Keycloak's token endpoint for a given jti at a time. This
                // matters once refresh token rotation is enabled in the realm - a refresh token
                // can only be redeemed once, so two concurrent requests refreshing the same
                // session would otherwise have the loser's call rejected with invalid_grant.
                if (refreshLockService.tryAcquire(jti)) {
                    try {
                        refreshTokens(session, jti);
                    } catch (HttpClientErrorException e) {
                        handleRefreshRejected(session, jti, e);
                    } catch (Exception e) {
                        log.error("Proactive Token Refresh failed: {}", e.getMessage());
                        // We continue the chain; the downstream service will likely return 401 if it's truly expired.
                    } finally {
                        refreshLockService.release(jti);
                    }
                } else {
                    // Another request (possibly on another instance) is already refreshing this
                    // session. Wait briefly for it to finish instead of racing it, then reload
                    // the session so we pick up whatever it left behind: fresh tokens if the
                    // refresh succeeded, or nothing if it failed and deleted the session (in
                    // which case the proxy below behaves exactly as it does today for a missing
                    // session - 401).
                    log.debug("Token refresh already in progress for jti={}; waiting for it to finish", jti);
                    refreshLockService.waitForRelease(jti);
                    if (sessionService.load(jti) == null) {
                        log.warn("Session jti={} is gone after waiting for a concurrent refresh (it likely failed)", jti);
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Handles Keycloak rejecting a refresh with {@code invalid_grant} (refresh token revoked,
     * expired, or - now that the realm has refresh token rotation enabled - already redeemed).
     * <p>
     * Holding {@code refreshLockService}'s lock should make it impossible for two BFF requests
     * to race each other to redeem the same refresh token, but the lock's TTL is a short safety
     * net, not a guarantee: if this call is unusually slow, another node could acquire the lock
     * after ours expired and refresh (and rotate) the token first. Reload the session before
     * deleting it - if it has changed since we started, another node already replaced it with
     * fresh tokens, and we were simply the loser of that race rather than looking at a genuinely
     * dead session.
     */
    private void handleRefreshRejected(BffSession attemptedSession, String jti, HttpClientErrorException e) {
        BffSession current = sessionService.load(jti);
        if (current != null && !current.refreshToken().equals(attemptedSession.refreshToken())) {
            log.info("Refresh token for jti={} was already rotated by another node; keeping its newer session", jti);
            return;
        }

        // The stored session can never be refreshed again, so delete it now: the proxy will
        // then return 401 for this and any subsequent request instead of forwarding a dead
        // access token downstream.
        log.warn("Proactive Token Refresh rejected by Keycloak for jti={}, deleting session: {}", jti, e.getMessage());
        sessionService.delete(jti);
    }

    private void refreshTokens(BffSession session, String jti) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(KEYCLOAK_REGISTRATION_ID);
        if (registration == null) {
            log.error("No '{}' client registration found; cannot refresh token for jti={}", KEYCLOAK_REGISTRATION_ID, jti);
            return;
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("refresh_token", session.refreshToken());
        formData.add("client_id", registration.getClientId());
        formData.add("client_secret", registration.getClientSecret());

        Map tokenResponse = restClient.post()
                .uri(registration.getProviderDetails().getTokenUri())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(formData)
                .retrieve()
                .body(Map.class);

        if (tokenResponse != null) {
            String newAccessTokenValue = (String) tokenResponse.get("access_token");
            String newRefreshTokenValue = (String) tokenResponse.get("refresh_token");
            Integer expiresIn = (Integer) tokenResponse.get("expires_in");

            Instant newExpiresAt = Instant.now().plusSeconds(expiresIn);

            BffSession updatedSession = new BffSession(
                    session.principalName(),
                    newAccessTokenValue,
                    newExpiresAt,
                    // Keycloak doesn't always rotate the refresh token; keep the old one if so.
                    newRefreshTokenValue != null ? newRefreshTokenValue : session.refreshToken(),
                    session.idToken(),
                    session.scopes());

            // 6. Save Updated Session to Redis
            sessionService.save(jti, updatedSession);
        }
    }
}

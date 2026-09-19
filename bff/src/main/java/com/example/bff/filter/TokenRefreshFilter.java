package com.example.bff.filter;

import com.example.bff.service.SessionRedisService;
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
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
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

    private final long refreshBufferSeconds;
    private final SessionRedisService sessionService;
    private final JwtUtils jwtUtils;
    private final RestClient restClient;

    public TokenRefreshFilter(SessionRedisService sessionService,
                              JwtUtils jwtUtils,
                              RestClient.Builder restClientBuilder,
                              @org.springframework.beans.factory.annotation.Value("${bff.token.refresh-buffer-seconds}") long refreshBufferSeconds) {
        this.sessionService = sessionService;
        this.jwtUtils = jwtUtils;
        // Using Builder allows us to inject a mock/custom builder in tests
        this.restClient = restClientBuilder.build();
        this.refreshBufferSeconds = refreshBufferSeconds;
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

        // 3. Load OAuth2 Context from Redis
        OAuth2AuthorizedClient client = sessionService.load(jti);
        if (client == null || client.getRefreshToken() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Check for Proactive Refresh Condition
        OAuth2AccessToken accessToken = client.getAccessToken();
        if (accessToken.getExpiresAt() != null) {
            long secondsRemaining = accessToken.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            
            if (secondsRemaining < refreshBufferSeconds) {
                try {
                    // 5. Execute Manual Refresh via Keycloak
                    refreshTokens(client, jti);
                } catch (HttpClientErrorException e) {
                    // Keycloak rejected the refresh (e.g. invalid_grant because the refresh
                    // token was revoked or expired). The stored session can never be refreshed
                    // again, so delete it now: the proxy will then return 401 for this and any
                    // subsequent request instead of forwarding a dead access token downstream.
                    log.warn("Proactive Token Refresh rejected by Keycloak for jti={}, deleting session: {}", jti, e.getMessage());
                    sessionService.delete(jti);
                } catch (Exception e) {
                    log.error("Proactive Token Refresh failed: {}", e.getMessage());
                    // We continue the chain; the downstream service will likely return 401 if it's truly expired.
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void refreshTokens(OAuth2AuthorizedClient client, String jti) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("refresh_token", client.getRefreshToken().getTokenValue());
        formData.add("client_id", client.getClientRegistration().getClientId());
        formData.add("client_secret", client.getClientRegistration().getClientSecret());

        Map tokenResponse = restClient.post()
                .uri(client.getClientRegistration().getProviderDetails().getTokenUri())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(formData)
                .retrieve()
                .body(Map.class);

        if (tokenResponse != null) {
            String newAccessTokenValue = (String) tokenResponse.get("access_token");
            String newRefreshTokenValue = (String) tokenResponse.get("refresh_token");
            Integer expiresIn = (Integer) tokenResponse.get("expires_in");
            
            Instant newExpiresAt = Instant.now().plusSeconds(expiresIn);
            
            OAuth2AccessToken newAccessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    newAccessTokenValue,
                    Instant.now(),
                    newExpiresAt,
                    client.getAccessToken().getScopes());
            
            OAuth2RefreshToken newRefreshToken = newRefreshTokenValue != null 
                    ? new OAuth2RefreshToken(newRefreshTokenValue, Instant.now()) 
                    : client.getRefreshToken();

            OAuth2AuthorizedClient updatedClient = new OAuth2AuthorizedClient(
                    client.getClientRegistration(),
                    client.getPrincipalName(),
                    newAccessToken,
                    newRefreshToken);

            // 6. Save Updated Tokens to Redis
            sessionService.save(jti, updatedClient);
        }
    }
}

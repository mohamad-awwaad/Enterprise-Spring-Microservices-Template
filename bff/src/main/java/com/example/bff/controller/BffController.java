package com.example.bff.controller;

import com.example.common.core.constant.SessionConstants;
import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Backend-for-Frontend (BFF) controller handling OAuth2 authentication and API proxying.
 * <p>
 * This controller manages two distinct authentication mechanisms:
 * <ul>
 *   <li><b>Spring Security OAuth2 session</b> - Standard JSESSIONID-based session created
 *       during OAuth2 login. Used by the /user endpoint to return identity claims.</li>
 *   <li><b>Custom BFF session</b> - BFF_SESSION cookie containing a signed JWT with a JTI
 *       that maps to OAuth2 tokens stored in Redis. Used by /api/** proxy endpoints.</li>
 * </ul>
 * <p>
 * The dual mechanism exists because:
 * <ul>
 *   <li>/user needs Spring Security's OAuth2 integration to access OIDC claims</li>
 *   <li>/api/** needs custom session handling for proactive token refresh and
 *       fine-grained control over the gateway proxy behavior</li>
 * </ul>
 * <p>
 * Both mechanisms must be properly cleared during logout to prevent authentication leaks.
 */
@RestController
@RequestMapping("/bff")
@Slf4j
public class BffController {

    private final OAuth2AuthorizedClientService clientService;
    private final SessionRedisService sessionService;
    private final JwtUtils jwtUtils;
    private final RestClient restClient;
    private final CircuitBreaker gatewayCircuitBreaker;
    private final Environment env;

    /**
     * {@code RestClient.Builder} is a prototype-scoped bean (a fresh, pre-configured builder
     * per injection point), not a singleton to be re-built from on every request. Building the
     * client once here - instead of calling {@code restClientBuilder.build()} per proxy call -
     * means we keep Boot's auto-configured builder (Micrometer observation/trace propagation,
     * {@code spring.http.clients.*} timeouts) instead of discarding it on every request.
     */
    public BffController(OAuth2AuthorizedClientService clientService,
                          SessionRedisService sessionService,
                          JwtUtils jwtUtils,
                          RestClient.Builder restClientBuilder,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          Environment env) {
        this.clientService = clientService;
        this.sessionService = sessionService;
        this.jwtUtils = jwtUtils;
        this.restClient = restClientBuilder.build();
        this.gatewayCircuitBreaker = circuitBreakerRegistry.circuitBreaker("gateway");
        this.env = env;
    }

    @Value("${bff.gateway.url}")
    private String gatewayUrl;

    @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
    private String issuerUri;

    @Value("${bff.cookie.secure}")
    private boolean cookieSecure;

    @Value("${bff.frontend.url}")
    private String frontendUrl;

    /**
     * Returns the authenticated user's identity claims from Keycloak.
     * <p>
     * This endpoint provides OIDC identity information (sub, email, preferred_username, name)
     * from the Identity Provider. It is used by the frontend to check authentication state
     * and display basic user info in the UI.
     * <p>
     * Note: This is separate from the Profile Service which stores application-specific
     * business data. The IdP is the source of truth for identity; the Profile Service
     * is the source of truth for application domain data.
     *
     * @param user The authenticated OIDC user (injected by Spring Security)
     * @return The OIDC user claims, or triggers 401 if not authenticated
     */
    @GetMapping("/user")
    public OidcUser user(@AuthenticationPrincipal OidcUser user) {
        return user;
    }

    @GetMapping("/login")
    public void login(HttpServletResponse res) throws IOException {
        res.sendRedirect("/oauth2/authorization/keycloak");
    }

    /**
     * Handles successful OAuth2 login by creating a BFF session.
     * <p>
     * This endpoint is called after Keycloak redirects back with an authorization code
     * and Spring Security exchanges it for tokens. It:
     * <ol>
     *   <li>Copies the token values (and the ID token, for logout) out of Spring Security's
     *       {@code OAuth2AuthorizedClient} into a {@link BffSession} record, stored in Redis
     *       keyed by a unique JTI</li>
     *   <li>Discards the {@code OAuth2AuthorizedClient} from the in-memory
     *       {@code OAuth2AuthorizedClientService} (see below)</li>
     *   <li>Issues a signed BFF_SESSION JWT cookie containing the JTI</li>
     *   <li>Redirects to the frontend application</li>
     * </ol>
     *
     * @param auth The OAuth2 authentication token from Spring Security
     * @return Redirect to frontend with BFF_SESSION cookie set
     */
    @GetMapping("/login/success")
    public ResponseEntity<?> loginSuccess(OAuth2AuthenticationToken auth) {
        OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(
                auth.getAuthorizedClientRegistrationId(), auth.getName());

        String idTokenValue = auth.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser.getIdToken().getTokenValue()
                : null;

        OAuth2AccessToken accessToken = client.getAccessToken();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        BffSession session = new BffSession(
                auth.getName(),
                accessToken.getTokenValue(),
                accessToken.getExpiresAt(),
                refreshToken != null ? refreshToken.getTokenValue() : null,
                idTokenValue,
                accessToken.getScopes());

        String jti = UUID.randomUUID().toString();
        sessionService.save(jti, session);

        /*
         * The in-memory OAuth2AuthorizedClientService keeps every authorized client for the
         * life of the process, keyed by (registrationId, principalName). Nothing in the BFF
         * ever reads it again after this point: /bff/user only needs the OidcUser principal
         * already held in the Spring Session (JSESSIONID), and /bff/api/** reads tokens from
         * the BffSession record in Redis via the jti. Left in place, this map would grow
         * without bound as users log in. Removing the entry now - right after copying what we
         * need into the BffSession - keeps the service's memory footprint bounded.
         */
        clientService.removeAuthorizedClient(auth.getAuthorizedClientRegistrationId(), auth.getName());

        String sessionJwt = jwtUtils.issueSessionJwt(jti, auth);

        ResponseCookie cookie = ResponseCookie.from(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .build();

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendUrl)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    /**
     * Performs complete logout across all authentication layers.
     * <p>
     * The BFF maintains two authentication mechanisms that must both be cleared:
     * <ul>
     *   <li><b>Spring Security session (JSESSIONID)</b> - Used by /bff/user endpoint</li>
     *   <li><b>BFF_SESSION cookie + Redis</b> - Used by /bff/api/** proxy endpoints</li>
     * </ul>
     * <p>
     * This endpoint:
     * <ol>
     *   <li>Extracts JTI from BFF_SESSION and deletes the Redis session</li>
     *   <li>Clears Spring SecurityContext and invalidates HTTP session</li>
     *   <li>Clears both BFF_SESSION and JSESSIONID cookies</li>
     *   <li>Redirects to Keycloak logout to invalidate the IdP session</li>
     *   <li>Keycloak then redirects back to the frontend login page</li>
     * </ol>
     * <p>
     * <b>Why POST:</b> logout mutates state (it invalidates the session server-side), so it must
     * go through Spring Security's CSRF check like any other state-changing request; it stays
     * {@code permitAll} for authorization purposes (an expired/missing session must still be able
     * to reach Keycloak's logout), but it is deliberately left out of the CSRF
     * {@code ignoringRequestMatchers} list so a cross-site {@code GET} (previously enough to log a
     * user out, a mild CSRF/DoS nuisance) can no longer trigger it. See
     * {@code AuthService.logout()} on the Angular side for how it submits the required
     * {@code _csrf} token as a hidden form field.
     *
     * @param sessionJwt The BFF_SESSION JWT cookie (optional, may be expired/missing)
     * @param request The HTTP request for session access
     * @return Redirect to Keycloak logout endpoint
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = SessionConstants.COOKIE_BFF_SESSION, required = false) String sessionJwt,
            HttpServletRequest request) {

        String idToken = null;

        if (sessionJwt != null) {
            try {
                String jti = jwtUtils.extractJti(sessionJwt);
                if (jti != null) {
                    BffSession session = sessionService.load(jti);
                    if (session != null) {
                        idToken = session.idToken();
                    }
                    sessionService.delete(jti);
                }
            } catch (Exception e) {
                // Ignore parsing errors on logout
            }
        }

        // Invalidate Spring Security session
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        ResponseCookie cookie = ResponseCookie.from(SessionConstants.COOKIE_BFF_SESSION, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();

        // Redirect to Keycloak logout endpoint
        String logoutUrl;
        if (idToken != null) {
            String redirectUri = frontendUrl + "/login";
            logoutUrl = issuerUri + "/protocol/openid-connect/logout?id_token_hint=" + idToken
                    + "&post_logout_redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        } else {
            // Fallback if no ID token (session expired or already logged out)
            logoutUrl = issuerUri + "/protocol/openid-connect/logout";
        }

        // Also clear JSESSIONID cookie
        ResponseCookie jsessionCookie = ResponseCookie.from(SessionConstants.COOKIE_JSESSIONID, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, logoutUrl)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.SET_COOKIE, jsessionCookie.toString())
                .build();
    }

    @RequestMapping(value = "/api/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<?> proxyRequest(
            HttpServletRequest request,
            @CookieValue(name = SessionConstants.COOKIE_BFF_SESSION, required = false) String sessionJwt,
            @RequestBody(required = false) byte[] body) {

        if (sessionJwt == null) {
            return buildErrorResponse(401, "MISSING_SESSION", "BFF_SESSION cookie not found");
        }

        String jti = jwtUtils.extractJti(sessionJwt);
        if (jti == null) {
            return buildErrorResponse(401, "INVALID_SESSION", "Failed to extract JTI from session JWT");
        }

        BffSession session = sessionService.load(jti);
        if (session == null) {
            return buildErrorResponse(401, "SESSION_NOT_FOUND", "Session not found in Redis (expired or invalid)");
        }

        String accessToken = session.accessToken();

        // Extract the path after "/bff/api"
        // Example: /bff/api/profile -> /profile, /bff/api/profile/foo -> /profile/foo
        String requestUri = request.getRequestURI();
        String path = requestUri.substring(requestUri.indexOf("/api") + 4); // Strip "/api"

        String queryString = request.getQueryString();
        URI targetUri = URI.create(gatewayUrl + path + (queryString != null ? "?" + queryString : ""));

        log.debug("Proxying request to: {}", targetUri);

        return proxyToGateway(request, targetUri, body, accessToken);
    }

    /**
     * Proxies public requests (registration, confirmation, api-docs) without authentication.
     * <p>
     * URL pattern: /bff/public/{service}/{path}
     * Example: /bff/public/profile/register → Gateway /profile/public/register
     * <p>
     * This follows the unified public endpoint pattern where all public paths are
     * accessed via /{service}/public/** at the gateway level.
     */
    @RequestMapping(value = "/public/{service}/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> proxyPublicRequest(
            HttpServletRequest request,
            @PathVariable String service,
            @RequestBody(required = false) byte[] body) {

        // Extract the path after "/bff/public/{service}"
        String requestUri = request.getRequestURI();
        String prefix = "/bff/public/" + service;
        String remainingPath = requestUri.substring(requestUri.indexOf(prefix) + prefix.length());

        // Transform: /bff/public/profile/register → /profile/public/register
        String targetPath = "/" + service + "/public" + remainingPath;

        String queryString = request.getQueryString();
        URI targetUri = URI.create(gatewayUrl + targetPath + (queryString != null ? "?" + queryString : ""));

        log.debug("Proxying public request to: {}", targetUri);

        return proxyToGateway(request, targetUri, body, null);
    }

    /**
     * Executes a proxied call to the Gateway through the {@code gateway} circuit breaker and
     * translates the outcome into the response the BFF sends back to the browser.
     * <p>
     * This is the single call site shared by {@link #proxyRequest} and
     * {@link #proxyPublicRequest} so the circuit breaker wraps one place instead of being
     * duplicated. Only infrastructure failures (5xx, connection errors, timeouts) count
     * towards the breaker's failure rate: {@code HttpClientErrorException} (4xx) is listed in
     * {@code resilience4j.circuitbreaker.instances.gateway.ignoreExceptions}, so it passes
     * through the breaker untouched and unrecorded - a 404 or 409 from a healthy downstream
     * service is a normal application response, not a sign the gateway is unavailable.
     *
     * @param request     the incoming servlet request (forwards method + a few headers)
     * @param targetUri   the resolved Gateway URI
     * @param body        the raw request body to forward (may be null for bodyless requests)
     * @param bearerToken the access token to forward as {@code Authorization: Bearer ...},
     *                    or {@code null} for unauthenticated public requests
     */
    private ResponseEntity<?> proxyToGateway(HttpServletRequest request, URI targetUri, byte[] body, String bearerToken) {
        try {
            return gatewayCircuitBreaker.executeSupplier(() -> callGateway(request, targetUri, body, bearerToken));
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Proxy request failed. Target: {}, Status: {}, Body: {}", targetUri, e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());
        } catch (CallNotPermittedException e) {
            // Circuit is OPEN: the gateway has been failing repeatedly, so fail fast instead
            // of piling on more timeouts against an already-struggling downstream.
            log.warn("Circuit breaker 'gateway' is OPEN; short-circuiting request to {}", targetUri);
            ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
            problemDetail.setTitle("Service Unavailable");
            problemDetail.setDetail("Gateway is temporarily unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problemDetail);
        } catch (ResourceAccessException e) {
            // Connection refused / timeout while the circuit is still CLOSED (or HALF_OPEN):
            // the gateway itself is unreachable, which is a 502 from the caller's point of view,
            // not an internal BFF error. These calls count towards opening the breaker.
            log.error("Gateway unreachable for {}: {}", targetUri, e.getMessage());
            ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
            problemDetail.setTitle("Bad Gateway");
            problemDetail.setDetail("Gateway is unreachable");
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problemDetail);
        } catch (Exception e) {
            log.error("Unexpected error during proxy request to {}", targetUri, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ResponseEntity<byte[]> callGateway(HttpServletRequest request, URI targetUri, byte[] body, String bearerToken) {
        ResponseEntity<byte[]> downstreamResponse = restClient.method(HttpMethod.valueOf(request.getMethod()))
                .uri(targetUri)
                .headers(h -> {
                    if (bearerToken != null) {
                        h.setBearerAuth(bearerToken);
                    }
                    // Forward Content-Type header (critical for POST/PUT requests with bodies)
                    // so the downstream service knows how to parse the payload (e.g., application/json).
                    if (request.getContentType() != null) {
                        h.setContentType(MediaType.parseMediaType(request.getContentType()));
                    }
                    // Forward content-negotiation headers so downstream error bodies and
                    // localized messages match what the caller actually asked for.
                    if (request.getHeader(HttpHeaders.ACCEPT) != null) {
                        h.set(HttpHeaders.ACCEPT, request.getHeader(HttpHeaders.ACCEPT));
                    }
                    if (request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) != null) {
                        h.set(HttpHeaders.ACCEPT_LANGUAGE, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
                    }
                })
                .body(body != null ? body : new byte[0])
                .retrieve()
                .toEntity(byte[].class);

        return ResponseEntity.status(downstreamResponse.getStatusCode())
                .headers(stripHopByHopHeaders(downstreamResponse.getHeaders()))
                .body(downstreamResponse.getBody());
    }

    /**
     * Copies downstream response headers for forwarding, dropping hop-by-hop / framing headers
     * that must not be relayed verbatim: {@code Transfer-Encoding}/{@code Connection}/
     * {@code Keep-Alive} are per-connection only, and {@code Content-Length} is recomputed by
     * Tomcat for the response body we actually write here.
     */
    private static HttpHeaders stripHopByHopHeaders(HttpHeaders downstreamHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(downstreamHeaders);
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.CONNECTION);
        headers.remove("Keep-Alive");
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        return headers;
    }

    /**
     * Builds an error response with descriptive details in non-production environments.
     * In production, returns a generic 401 response to avoid leaking internal details.
     */
    private ResponseEntity<?> buildErrorResponse(int status, String errorCode, String message) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status)
                .body(Map.of(
                    "status", status,
                    "error", errorCode,
                    "message", message
                ));
    }
}
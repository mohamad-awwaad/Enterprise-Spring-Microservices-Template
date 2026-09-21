package com.example.bff;

import com.example.common.core.constant.SessionConstants;
import com.example.common.test.KeycloakTestContainer;
import com.example.common.test.RedisTestContainer;
import com.example.bff.config.TestConfig;
import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the BFF's OAuth2 login gate and its {@code /bff/api/**} proxy, hermetically:
 * <ul>
 *   <li>a real (containerized) Keycloak issues the session's access token - see
 *       {@link KeycloakTestContainer};</li>
 *   <li>a real (containerized) Redis backs the session store - see {@link RedisTestContainer};</li>
 *   <li>the downstream Gateway the BFF would normally proxy to is replaced by a
 *       {@link MockWebServer} (see {@link #mockGateway}), since exercising the real Gateway and
 *       profile-service is out of scope for a BFF-module test and would defeat hermeticity.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
class KeycloakIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIntegrationTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // CSRF protection is enabled in all profiles (see SecurityConfig's SPA recipe), so write
    // requests must echo the token back as both a cookie and this header - exactly what
    // Angular's built-in XSRF interceptor does for real browser requests.
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    private static final KeycloakTestContainer keycloak = KeycloakTestContainer.getInstance();
    private static final RedisTestContainer redis = RedisTestContainer.getInstance();

    /**
     * Stands in for the Gateway the BFF proxies {@code /bff/api/**} to. A stateful
     * {@link Dispatcher} (rather than a fixed {@code enqueue(...)} sequence) is used because the
     * request order isn't fully deterministic across test methods: {@code @BeforeEach} itself
     * makes a cleanup GET+DELETE call before every test.
     */
    private static final MockWebServer mockGateway = new MockWebServer();
    private static final AtomicReference<byte[]> storedProfile = new AtomicReference<>();

    static {
        try {
            mockGateway.setDispatcher(new ProfileGatewayDispatcher());
            mockGateway.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start mock gateway", e);
        }
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak::issuerUri);
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);
        registry.add("bff.gateway.url", () -> "http://127.0.0.1:" + mockGateway.getPort());
    }

    @AfterAll
    static void shutdownMockGateway() throws IOException {
        mockGateway.shutdown();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRedisService sessionService;

    @Autowired
    private JwtUtils jwtUtils;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        cleanupProfile();
    }

    private void cleanupProfile() {
        try {
            log.debug("Attempting to clean up existing profile before test...");
            String sessionJwt = obtainSessionJwt();
            String csrfToken = obtainCsrfToken(sessionJwt);
            // We use exchange() and don't assert status because a 404 is perfectly fine
            // (it just means there was no leftover data to clean up).
            webTestClient.delete().uri("/bff/api/profile")
                    .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                    .cookie(CSRF_COOKIE_NAME, csrfToken)
                    .header(CSRF_HEADER_NAME, csrfToken)
                    .exchange();
        } catch (Exception e) {
            log.warn("Pre-test cleanup skipped: {}. This is normal if no profile exists yet.", e.getMessage());
        }
    }

    /**
     * Obtains a fresh CSRF token by issuing a GET request (a "safe" method that CsrfFilter
     * never rejects) with a valid session. CsrfCookieFilter forces the token to resolve on
     * every request, so the XSRF-TOKEN cookie comes back on this response even though GET
     * itself doesn't require CSRF protection.
     */
    private String obtainCsrfToken(String sessionJwt) {
        var result = webTestClient.get().uri("/bff/api/profile")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .exchange()
                .returnResult(Void.class);

        ResponseCookie csrfCookie = result.getResponseCookies().getFirst(CSRF_COOKIE_NAME);
        assertThat(csrfCookie)
                .as("%s cookie must be set once a session exists", CSRF_COOKIE_NAME)
                .isNotNull();
        return csrfCookie.getValue();
    }

    private String obtainSessionJwt() {
        // 1. Get a real token from the containerized Keycloak.
        // bff-client no longer allows the password grant (directAccessGrantsEnabled=false -
        // it only performs the authorization_code flow in real usage); test-client is a
        // confidential client dedicated to integration tests and kept enabled for it.
        String accessTokenValue = keycloak.passwordGrantToken("user", "password");

        // 2. Create Session in Redis manually to simulate logged-in state
        String jti = UUID.randomUUID().toString();

        BffSession session = new BffSession(
                "user",
                accessTokenValue,
                Instant.now().plusSeconds(300),
                "dummy-refresh-token",
                "dummy-token-value",
                Set.of("openid", "profile", "email"));

        sessionService.save(jti, session);

        // 3. Generate Session Cookie
        OidcIdToken idToken = new OidcIdToken("dummy-token-value", Instant.now(), Instant.now().plusSeconds(60), Map.of("sub", "user", "email", "user@example.com", "name", "User Name"));
        OidcUser oidcUser = new DefaultOidcUser(Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")), idToken, "sub");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");

        return jwtUtils.issueSessionJwt(jti, auth);
    }

    @Test
    void testRedirectToKeycloakForProtectedResource() {
        // Test that accessing a protected resource without a session redirects to Keycloak
        webTestClient.get().uri("/bff/user")
                .exchange()
                .expectStatus().isFound() // 302 Redirect
                .expectHeader().value("Location", loc ->
                    assertThat(loc).contains("/oauth2/authorization/keycloak"));
    }

    @Test
    void testProxyToProfileService() {
        // 1. Obtain Session JWT
        String sessionJwt = obtainSessionJwt();

        // 1b. Obtain a CSRF token up front (via a GET, a safe method) - POST/DELETE below need
        // to present it as both a cookie and the X-XSRF-TOKEN header now that CSRF protection
        // is enabled in all profiles.
        String csrfToken = obtainCsrfToken(sessionJwt);

        // 2. Perform Request: Create Profile (POST)
        Map<String, Object> profileData = Map.of(
            "firstName", "Integration",
            "lastName", "Test",
            "email", "integration@test.com",
            "gender", "MALE",
            "age", 99
        );

        webTestClient.post().uri("/bff/api/profile")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .cookie(CSRF_COOKIE_NAME, csrfToken)
                .header(CSRF_HEADER_NAME, csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(profileData)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Integration");

        // 3. Perform Request: Get Profile (GET)
        webTestClient.get().uri("/bff/api/profile")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.lastName").isEqualTo("Test");

        // 4. Perform Request: Get Profile (GET) with Slash (Browser Simulation)
        // This validates that the Gateway StripPrefix and Profile Service handling work for trailing slash
        webTestClient.get().uri("/bff/api/profile/")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.lastName").isEqualTo("Test");

        // 5. Perform Request: Delete Profile (DELETE)
        webTestClient.delete().uri("/bff/api/profile")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .cookie(CSRF_COOKIE_NAME, csrfToken)
                .header(CSRF_HEADER_NAME, csrfToken)
                .exchange()
                .expectStatus().isNoContent();

        // 6. Verify Deletion (GET -> 404)
        webTestClient.get().uri("/bff/api/profile")
                .cookie(SessionConstants.COOKIE_BFF_SESSION, sessionJwt)
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Fakes just enough of the Gateway -> profile-service round trip that the BFF proxies
     * {@code /bff/api/profile} to: GET/POST/DELETE on {@code /profile} (and {@code /profile/},
     * since the BFF forwards trailing slashes verbatim). State (does a profile currently
     * "exist"?) is tracked across requests so the create/read/delete lifecycle the test drives
     * behaves like a real backend would, regardless of call order across test methods.
     */
    private static final class ProfileGatewayDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (!path.equals("/profile") && !path.equals("/profile/")) {
                return new MockResponse().setResponseCode(404);
            }
            return switch (request.getMethod()) {
                case "POST" -> {
                    storedProfile.set(request.getBody().readByteArray());
                    yield jsonResponse(200, storedProfile.get());
                }
                case "GET" -> {
                    byte[] body = storedProfile.get();
                    yield body != null ? jsonResponse(200, body) : new MockResponse().setResponseCode(404);
                }
                case "DELETE" -> {
                    boolean existed = storedProfile.getAndSet(null) != null;
                    yield new MockResponse().setResponseCode(existed ? 204 : 404);
                }
                default -> new MockResponse().setResponseCode(405);
            };
        }

        private static MockResponse jsonResponse(int status, byte[] body) {
            try {
                // Round-trip through Jackson just to fail fast if a test ever sends something
                // that isn't valid JSON, rather than silently echoing garbage back.
                JsonNode ignored = OBJECT_MAPPER.readTree(body);
                assertThat(ignored).isNotNull();
            } catch (IOException e) {
                throw new IllegalStateException("Expected JSON body", e);
            }
            return new MockResponse()
                    .setResponseCode(status)
                    .addHeader("Content-Type", "application/json")
                    .setBody(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}

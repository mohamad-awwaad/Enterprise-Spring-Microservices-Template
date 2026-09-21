package com.example.gateway;

import com.example.common.test.KeycloakTestContainer;
import com.example.common.test.RedisTestContainer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Gateway's routing/security behavior hermetically:
 * <ul>
 *   <li>a real (containerized) Keycloak issues bearer tokens - see {@link KeycloakTestContainer};</li>
 *   <li>a real (containerized) Redis backs the Gateway's Redis-based rate limiter - see
 *       {@link RedisTestContainer};</li>
 *   <li>the "profile-service" route is repointed at a {@link MockWebServer} so the actual routing
 *       (StripPrefix/PrefixPath, Authorization propagation) can be verified without a real
 *       profile-service running;</li>
 *   <li>the "order-service" route is repointed at a closed local port so the CircuitBreaker's
 *       fallback path can be exercised deterministically (connection refused, every time).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIntegrationTest {

    private static final KeycloakTestContainer keycloak = KeycloakTestContainer.getInstance();
    private static final RedisTestContainer redis = RedisTestContainer.getInstance();
    private static final MockWebServer mockProfileService = new MockWebServer();

    static {
        try {
            mockProfileService.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start mock profile-service", e);
        }
    }

    /**
     * Boot's relaxed binding for indexed list properties (like {@code routes[N]}) does not merge
     * per-index across property sources: once the highest-priority source (here, the properties
     * {@link DynamicPropertySource} adds) defines ANY {@code routes[n]} key, the size/content of
     * the WHOLE {@code routes} list is taken from that source alone - entries only present in
     * application.properties (lower priority) are dropped entirely, not merged in. So all four
     * routes must be fully re-declared here (uri aside, identical to application.properties);
     * omitting routes[2]/[3] silently drops them and every {@code /admin/**} request 404s.
     */
    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak::issuerUri);
        registry.add("spring.data.redis.host", redis::host);
        registry.add("spring.data.redis.port", redis::port);

        // routes[0] = profile-service (Path=/profile/**): repoint at our MockWebServer.
        String routes0 = "spring.cloud.gateway.server.webflux.routes[0].";
        registry.add(routes0 + "id", () -> "profile-service");
        registry.add(routes0 + "uri", () -> "http://127.0.0.1:" + mockProfileService.getPort());
        registry.add(routes0 + "predicates[0]", () -> "Path=/profile/**");
        registry.add(routes0 + "filters[0]", () -> "StripPrefix=1");
        registry.add(routes0 + "filters[1]", () -> "PrefixPath=/api");
        registry.add(routes0 + "filters[2].name", () -> "RequestRateLimiter");
        registry.add(routes0 + "filters[2].args.redis-rate-limiter.replenishRate", () -> "10");
        registry.add(routes0 + "filters[2].args.redis-rate-limiter.burstCapacity", () -> "20");
        registry.add(routes0 + "filters[2].args.key-resolver", () -> "#{@userKeyResolver}");
        registry.add(routes0 + "filters[3].name", () -> "CircuitBreaker");
        registry.add(routes0 + "filters[3].args.name", () -> "profileServiceCB");
        registry.add(routes0 + "filters[3].args.fallbackUri", () -> "forward:/fallback");

        // routes[1] = order-service (Path=/orders/**): repoint at a closed port so every call
        // fails with connection-refused, deterministically driving the CircuitBreaker fallback.
        String routes1 = "spring.cloud.gateway.server.webflux.routes[1].";
        registry.add(routes1 + "id", () -> "order-service");
        registry.add(routes1 + "uri", () -> "http://127.0.0.1:1");
        registry.add(routes1 + "predicates[0]", () -> "Path=/orders/**");
        registry.add(routes1 + "filters[0]", () -> "StripPrefix=1");
        registry.add(routes1 + "filters[1]", () -> "PrefixPath=/api");
        registry.add(routes1 + "filters[2].name", () -> "RequestRateLimiter");
        registry.add(routes1 + "filters[2].args.redis-rate-limiter.replenishRate", () -> "10");
        registry.add(routes1 + "filters[2].args.redis-rate-limiter.burstCapacity", () -> "20");
        registry.add(routes1 + "filters[2].args.key-resolver", () -> "#{@userKeyResolver}");
        registry.add(routes1 + "filters[3].name", () -> "CircuitBreaker");
        registry.add(routes1 + "filters[3].args.name", () -> "orderServiceCB");
        registry.add(routes1 + "filters[3].args.fallbackUri", () -> "forward:/fallback");

        // routes[2] = admin-service (Path=/admin/**): unchanged from application.properties, but
        // must be re-declared here too (see this method's Javadoc) or it disappears entirely.
        String routes2 = "spring.cloud.gateway.server.webflux.routes[2].";
        registry.add(routes2 + "id", () -> "admin-service");
        registry.add(routes2 + "uri", () -> "http://127.0.0.1:8084");
        registry.add(routes2 + "predicates[0]", () -> "Path=/admin/**");
        registry.add(routes2 + "filters[0]", () -> "StripPrefix=1");
        registry.add(routes2 + "filters[1]", () -> "PrefixPath=/api");
        registry.add(routes2 + "filters[2].name", () -> "RequestRateLimiter");
        registry.add(routes2 + "filters[2].args.redis-rate-limiter.replenishRate", () -> "10");
        registry.add(routes2 + "filters[2].args.redis-rate-limiter.burstCapacity", () -> "20");
        registry.add(routes2 + "filters[2].args.key-resolver", () -> "#{@userKeyResolver}");
        registry.add(routes2 + "filters[3].name", () -> "CircuitBreaker");
        registry.add(routes2 + "filters[3].args.name", () -> "adminServiceCB");
        registry.add(routes2 + "filters[3].args.fallbackUri", () -> "forward:/fallback");

        // routes[3] = block-internal-registration: unchanged from application.properties, but
        // must be re-declared here too (see this method's Javadoc) or it disappears entirely.
        String routes3 = "spring.cloud.gateway.server.webflux.routes[3].";
        registry.add(routes3 + "id", () -> "block-internal-registration");
        registry.add(routes3 + "order", () -> "-1");
        registry.add(routes3 + "uri", () -> "no://op");
        registry.add(routes3 + "predicates[0]", () -> "Path=/admin/users/register");
        registry.add(routes3 + "filters[0]", () -> "SetStatus=403");
    }

    @AfterAll
    static void shutdownMockProfileService() throws IOException {
        mockProfileService.shutdown();
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void requestWithoutTokenIsRejected() {
        webTestClient.get().uri("/profile/foo")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validUserTokenIsRoutedToDownstreamWithAuthorization() throws InterruptedException {
        mockProfileService.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"firstName\":\"Alice\"}"));

        String token = keycloak.passwordGrantToken("user", "password");

        webTestClient.get().uri("/profile/foo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Alice");

        RecordedRequest recorded = mockProfileService.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).as("profile-service should have received the routed request").isNotNull();
        // StripPrefix=1 removes "/profile", PrefixPath=/api prepends "/api" -> "/api/foo".
        assertThat(recorded.getPath()).isEqualTo("/api/foo");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void internalRegistrationRouteIsBlockedWithoutAuthentication() {
        // Security requires authentication before the route's own SetStatus=403 filter ever runs.
        webTestClient.post().uri("/admin/users/register")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void internalRegistrationRouteIsBlockedForAuthenticatedCallers() {
        // Even a valid, authenticated token is refused: the "block-internal-registration" route
        // (order=-1, higher priority than the generic /admin/** route) unconditionally returns
        // 403 - this endpoint is only ever meant to be reached service-to-service, never through
        // the Gateway. See AdminController.registerUser's Javadoc.
        String token = keycloak.passwordGrantToken("user", "password");

        webTestClient.post().uri("/admin/users/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void downstreamFailureTriggersFallbackWith503ProblemDetail() {
        String token = keycloak.passwordGrantToken("user", "password");

        webTestClient.get().uri("/orders/foo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Service Unavailable");
    }
}

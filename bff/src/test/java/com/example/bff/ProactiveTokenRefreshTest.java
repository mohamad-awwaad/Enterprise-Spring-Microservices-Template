package com.example.bff;

import com.example.bff.filter.TokenRefreshFilter;
import com.example.bff.service.RefreshLockService;
import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import com.example.common.test.RedisTestContainer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ProactiveTokenRefreshTest {

    /**
     * Real (containerized) Redis backs {@link RefreshLockService} in these tests instead of a
     * mock: the whole point of the lock is genuine cross-request mutual exclusion via Redis's
     * {@code SET NX}, which a mock cannot exercise meaningfully. Shared across test methods like
     * {@link com.example.bff.KeycloakIntegrationTest} already does.
     */
    private static final RedisTestContainer redis = RedisTestContainer.getInstance();
    private static LettuceConnectionFactory connectionFactory;

    private MockWebServer mockWebServer;
    private SessionRedisService sessionService;
    private JwtUtils jwtUtils;
    private RefreshLockService refreshLockService;
    private TokenRefreshFilter filter;

    @BeforeAll
    static void startRedisConnection() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.host(), redis.port()));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedisConnection() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setup() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        sessionService = mock(SessionRedisService.class);
        jwtUtils = mock(JwtUtils.class);

        StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(connectionFactory);
        stringRedisTemplate.afterPropertiesSet();
        refreshLockService = new RefreshLockService(stringRedisTemplate);

        // TokenRefreshFilter no longer reads client id/secret/token URI off the stored
        // session (BffSession carries no ClientRegistration - see its Javadoc), so it looks
        // the "keycloak" registration up from a ClientRegistrationRepository instead.
        ClientRegistration reg = ClientRegistration.withRegistrationId("keycloak")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("bff-client")
                .clientSecret("secret")
                .redirectUri("{baseUrl}/login/code")
                .authorizationUri("http://auth")
                .tokenUri(mockWebServer.url("/token").toString()) // Important!
                .build();
        ClientRegistrationRepository clientRegistrationRepository = new InMemoryClientRegistrationRepository(reg);

        // Configure RestClient to hit MockWebServer
        RestClient.Builder builder = RestClient.builder().baseUrl(mockWebServer.url("/").toString());

        // Use 60 seconds as the refresh buffer (same as default in application.properties)
        filter = new TokenRefreshFilter(sessionService, jwtUtils, builder, clientRegistrationRepository, 60L, refreshLockService);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldRefreshExpiredToken() throws Exception {
        // 1. Setup Request
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bff/api/orders");
        request.setCookies(new Cookie("BFF_SESSION", "mock.jwt.token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // 2. Mock JWT Extraction
        when(jwtUtils.extractJti(anyString())).thenReturn("mock-jti");

        // 3. Mock Redis - Return session with expiring token
        // Expiring in 30 seconds (Condition < 60s)
        Instant expiresAt = Instant.now().plusSeconds(30);
        BffSession session = new BffSession(
                "user", "old-token", expiresAt, "refresh-token", "id-token-value", Set.of("openid"));
        when(sessionService.load("mock-jti")).thenReturn(session);

        // 4. Mock Keycloak Response
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\",\"expires_in\":300}")
                .addHeader("Content-Type", "application/json"));

        // 5. Execute Filter
        filter.doFilter(request, response, filterChain);

        // 6. Verify Refresh Happened
        // Verify Keycloak was called
        assertEquals(1, mockWebServer.getRequestCount());

        // Verify Redis Save was called with NEW token
        ArgumentCaptor<BffSession> captor = ArgumentCaptor.forClass(BffSession.class);
        verify(sessionService).save(eq("mock-jti"), captor.capture());

        BffSession savedSession = captor.getValue();
        assertEquals("new-access-token", savedSession.accessToken());
        assertEquals("new-refresh-token", savedSession.refreshToken());
        // Fields that aren't part of the refresh response must be carried over unchanged.
        assertEquals("id-token-value", savedSession.idToken());
        assertEquals("user", savedSession.principalName());

        // Verify Chain continued
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotTriggerRefreshForNonProxyPath() throws Exception {
        // Regression test for the prefix bug: the filter must only look at "/bff/api/**"
        // requests. A request to a non-proxy BFF endpoint (e.g. /bff/user, handled by
        // Spring Security's own OAuth2 session) must never reach Keycloak.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bff/user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertEquals(0, mockWebServer.getRequestCount());
        verifyNoInteractions(sessionService, jwtUtils);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldDeleteSessionWhenRefreshTokenRejectedByKeycloak() throws Exception {
        // 1. Setup Request
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bff/api/orders");
        request.setCookies(new Cookie("BFF_SESSION", "mock.jwt.token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // 2. Mock JWT Extraction
        when(jwtUtils.extractJti(anyString())).thenReturn("mock-jti");

        // 3. Mock Redis - Return session with expiring token
        Instant expiresAt = Instant.now().plusSeconds(30);
        BffSession session = new BffSession(
                "user", "old-token", expiresAt, "revoked-refresh-token", "id-token-value", Set.of("openid"));
        when(sessionService.load("mock-jti")).thenReturn(session);

        // 4. Mock Keycloak rejecting the refresh (e.g. invalid_grant: refresh token revoked/expired)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}")
                .addHeader("Content-Type", "application/json"));

        // 5. Execute Filter
        filter.doFilter(request, response, filterChain);

        // 6. Verify the dead session was deleted instead of being saved with a still-dead token
        verify(sessionService).delete("mock-jti");
        verify(sessionService, never()).save(anyString(), any());

        // 7. Verify the chain still continued so the proxy handles the now-missing session (401)
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldRefreshOnlyOnceForConcurrentRequestsNearExpiry() throws Exception {
        // Two requests for the SAME session arrive "at the same time", both seeing an
        // access token that's about to expire. Without the per-jti Redis lock, both would call
        // Keycloak with the same refresh token - and with refresh token rotation enabled in the
        // realm, the loser would be rejected with invalid_grant. With the lock, only one of them
        // may actually call Keycloak; the other waits for it to finish instead.
        String jti = "concurrent-refresh-jti";
        when(jwtUtils.extractJti(anyString())).thenReturn(jti);

        Instant expiresAt = Instant.now().plusSeconds(30);
        BffSession session = new BffSession(
                "user", "old-token", expiresAt, "refresh-token", "id-token-value", Set.of("openid"));
        when(sessionService.load(jti)).thenReturn(session);

        // A small delay widens the window in which the second request can observe the lock
        // already held, without making the test slow.
        mockWebServer.enqueue(new MockResponse()
                .setBodyDelay(150, TimeUnit.MILLISECONDS)
                .setBody("{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\",\"expires_in\":300}")
                .addHeader("Content-Type", "application/json"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<FilterChain> task = () -> {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setRequestURI("/bff/api/orders");
                request.setCookies(new Cookie("BFF_SESSION", "mock.jwt.token"));
                MockHttpServletResponse response = new MockHttpServletResponse();
                FilterChain filterChain = mock(FilterChain.class);
                filter.doFilter(request, response, filterChain);
                verify(filterChain).doFilter(request, response);
                return filterChain;
            };

            Future<FilterChain> first = executor.submit(task);
            Future<FilterChain> second = executor.submit(task);

            // Both requests must complete (neither hangs waiting on the other forever).
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        // Exactly one of the two requests actually called Keycloak's token endpoint...
        assertEquals(1, mockWebServer.getRequestCount());
        // ...and exactly one session save happened, with the refreshed tokens.
        ArgumentCaptor<BffSession> captor = ArgumentCaptor.forClass(BffSession.class);
        verify(sessionService, times(1)).save(eq(jti), captor.capture());
        assertEquals("new-access-token", captor.getValue().accessToken());
        assertEquals("new-refresh-token", captor.getValue().refreshToken());
    }

    @Test
    void shouldWaitForConcurrentRefreshInsteadOfDuplicatingIt() throws Exception {
        // Simulates another BFF instance/thread already holding the refresh lock for this
        // session (rather than racing two real requests, which makes the "arrives while the
        // lock is already held" window deterministic instead of timing-dependent).
        String jti = "waiting-for-lock-jti";
        when(jwtUtils.extractJti(anyString())).thenReturn(jti);

        Instant expiresAt = Instant.now().plusSeconds(30);
        BffSession staleSession = new BffSession(
                "user", "old-token", expiresAt, "refresh-token", "id-token-value", Set.of("openid"));
        BffSession refreshedSession = new BffSession(
                "user", "new-access-token", Instant.now().plusSeconds(300), "new-refresh-token", "id-token-value", Set.of("openid"));

        // First load (by our filter, before it notices the lock is held) sees the stale
        // session; once the "other node" finishes and we reload after waiting, we must see
        // what it left behind.
        when(sessionService.load(jti)).thenReturn(staleSession).thenReturn(refreshedSession);

        assertTrue(refreshLockService.tryAcquire(jti), "test setup: acquiring the lock as the simulated other node");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bff/api/orders");
        request.setCookies(new Cookie("BFF_SESSION", "mock.jwt.token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    filter.doFilter(request, response, filterChain);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Give the filter time to see the lock held and start polling; it must still be
            // waiting - it must never perform its own refresh while the lock is held.
            Thread.sleep(300);
            assertFalse(future.isDone(), "request should still be waiting on the held lock");
            assertEquals(0, mockWebServer.getRequestCount());

            // The "other node" finishes its refresh and releases the lock.
            refreshLockService.release(jti);

            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        // The waiting request never called Keycloak or saved a session itself...
        assertEquals(0, mockWebServer.getRequestCount());
        verify(sessionService, never()).save(anyString(), any());
        // ...it reloaded the session after waiting, picking up the refreshed tokens...
        verify(sessionService, times(2)).load(jti);
        // ...and the request still proceeded down the chain.
        verify(filterChain).doFilter(request, response);
    }
}

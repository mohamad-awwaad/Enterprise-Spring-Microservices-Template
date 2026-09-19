package com.example.bff;

import com.example.bff.filter.TokenRefreshFilter;
import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProactiveTokenRefreshTest {

    private MockWebServer mockWebServer;
    private SessionRedisService sessionService;
    private JwtUtils jwtUtils;
    private TokenRefreshFilter filter;

    @BeforeEach
    void setup() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        sessionService = mock(SessionRedisService.class);
        jwtUtils = mock(JwtUtils.class);

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
        filter = new TokenRefreshFilter(sessionService, jwtUtils, builder, clientRegistrationRepository, 60L);
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
}

package com.example.bff;

import com.example.common.core.constant.SessionConstants;
import com.example.bff.config.TestConfig;
import com.example.bff.service.SessionRedisService;
import com.example.bff.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
class KeycloakIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIntegrationTest.class);

    // CSRF protection is enabled in all profiles (see SecurityConfig's SPA recipe), so write
    // requests must echo the token back as both a cookie and this header - exactly what
    // Angular's built-in XSRF interceptor does for real browser requests.
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

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
            log.warn("Pre-test cleanup skipped: {}. This is normal if the profile service or Keycloak are not yet reachable, or if no profile exists.", e.getMessage());
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
        // 1. Get Token from Keycloak
        String tokenUrl = "http://localhost:8080/realms/my-realm/protocol/openid-connect/token";
        
        RestClient restClient = RestClient.create();

        // bff-client no longer allows the password grant (directAccessGrantsEnabled=false -
        // it only performs the authorization_code flow in real usage); test-client is a
        // confidential client dedicated to integration tests/curl and kept enabled for it.
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", "test-client");
        formData.add("client_secret", "test-secret");
        formData.add("grant_type", "password");
        formData.add("username", "user");
        formData.add("password", "password");

        Map tokenResponse = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(Map.class);
        
        assertThat(tokenResponse).isNotNull();
        String accessTokenValue = (String) tokenResponse.get("access_token");
        String refreshTokenValue = (String) tokenResponse.get("refresh_token");

        // 2. Create Session in Redis manually to simulate logged-in state
        String jti = UUID.randomUUID().toString();
        
        ClientRegistration clientRegistration = ClientRegistration.withRegistrationId("keycloak")
                .clientId("bff-client")
                .clientSecret("mysecret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .authorizationUri("http://localhost:8080/realms/my-realm/protocol/openid-connect/auth")
                .tokenUri(tokenUrl)
                .userInfoUri("http://localhost:8080/realms/my-realm/protocol/openid-connect/userinfo")
                .userNameAttributeName("preferred_username")
                .build();

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessTokenValue, Instant.now(), Instant.now().plusSeconds(300));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(refreshTokenValue, Instant.now());

        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(
                clientRegistration, "user", accessToken, refreshToken);

        sessionService.save(jti, authorizedClient);

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

}

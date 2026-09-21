package com.example.adminservice;

import com.example.common.test.KeycloakTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link com.example.adminservice.controller.AdminController} against a real
 * (containerized) Keycloak - see {@link KeycloakTestContainer} - instead of the local dev
 * Keycloak instance. Both the resource-server side (JWT validation/roles on incoming requests)
 * and the admin-client side (the real Keycloak Admin REST API, via {@code admin-service-client}'s
 * service account) are exercised against the same container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControllerIntegrationTest {

    private static final String REALM = "my-realm";
    private static final KeycloakTestContainer keycloak = KeycloakTestContainer.getInstance();

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak::issuerUri);
        registry.add("keycloak.admin.server-url", keycloak::serverUrl);
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    /** A raw admin client, used only to assert directly against Keycloak (not through the service under test). */
    private Keycloak adminApiClient;

    private WebTestClient client() {
        if (webTestClient == null) {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }
        return webTestClient;
    }

    private Keycloak adminApiClient() {
        if (adminApiClient == null) {
            adminApiClient = KeycloakBuilder.builder()
                    .serverUrl(keycloak.serverUrl())
                    .realm(REALM)
                    .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                    .clientId("admin-service-client")
                    .clientSecret("admin-secret")
                    .build();
        }
        return adminApiClient;
    }

    @AfterEach
    void tearDown() {
        if (adminApiClient != null) {
            adminApiClient.close();
            adminApiClient = null;
        }
        webTestClient = null;
    }

    // ---- POST /api/users/register (internal, service-to-service only) ----

    @Test
    void registerWithoutTokenIsUnauthorized() {
        client().post().uri("/api/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nobody", "email", "nobody@example.com"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void registerWithUserTokenIsForbidden() {
        // "user" is a plain human account (no INTERNAL_SERVICE realm role) - this endpoint is
        // reserved for service-to-service calls, see AdminController.registerUser's Javadoc.
        String token = keycloak.passwordGrantToken("user", "password");

        client().post().uri("/api/users/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nobody", "email", "nobody@example.com"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void registerWithInternalClientTokenCreatesUserInKeycloak() {
        // internal-client's service account carries the INTERNAL_SERVICE realm role (see the
        // realm export), which is exactly what @PreAuthorize("hasRole('INTERNAL_SERVICE')")
        // requires on this endpoint.
        String token = keycloak.clientCredentialsToken("internal-client", "my-internal-secret");

        String username = "registered-" + UUID.randomUUID();
        Map<String, Object> registration = Map.of(
                "username", username,
                "email", username + "@example.com",
                "firstName", "New",
                "lastName", "User",
                "sendPasswordEmail", false // no SMTP in this hermetic test - avoid the email side effect
        );

        String createdUserId = client().post().uri("/api/users/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registration)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(createdUserId).isNotBlank();

        // Verify directly against Keycloak's own admin API that the user now exists there.
        UserRepresentation created = adminApiClient().realm(REALM).users().get(createdUserId).toRepresentation();
        assertThat(created.getUsername()).isEqualTo(username);
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.isEnabled()).isTrue();
    }

    // ---- POST /api/users (admin-only user creation) ----

    @Test
    void createUserWithUserRoleIsForbidden() {
        String token = keycloak.passwordGrantToken("user", "password");

        client().post().uri("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "should-not-be-created", "email", "x@example.com"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void createUserWithAdminRoleSucceeds() {
        // "admin" carries the ADMIN realm role in the realm export - the only role this
        // endpoint's @PreAuthorize("hasRole('ADMIN')") accepts.
        String token = keycloak.passwordGrantToken("admin", "password");

        String username = "admin-created-" + UUID.randomUUID();
        Map<String, Object> newUser = Map.of(
                "username", username,
                "email", username + "@example.com",
                "firstName", "Admin",
                "lastName", "Created"
        );

        String createdUserId = client().post().uri("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newUser)
                .exchange()
                // AdminController.createUser actually returns 201 Created (not 200) on success.
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(createdUserId).isNotBlank();

        List<UserRepresentation> found = adminApiClient().realm(REALM).users().search(username);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getId()).isEqualTo(createdUserId);
    }
}

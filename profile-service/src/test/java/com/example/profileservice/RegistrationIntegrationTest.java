package com.example.profileservice;

import com.example.common.test.KeycloakTestContainer;
import com.example.profileservice.dto.SelfRegistrationRequest;
import com.example.profileservice.model.Gender;
import com.example.profileservice.repository.PendingRegistrationEntityRepository;
import com.example.profileservice.repository.UserProfileEntityRepository;
import com.example.profileservice.service.EmailService;
import com.example.profileservice.service.KeycloakAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.AfterAll;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.idle-timeout=10000",
                "spring.datasource.hikari.max-lifetime=10000",
                "app.registration.confirmation-base-url=http://localhost:4200/confirm"
})
@Testcontainers
class RegistrationIntegrationTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

        // The resource-server auto-configuration resolves the JWT issuer's OIDC discovery
        // document eagerly at context startup (JwtDecoders.fromIssuerLocation), so a reachable
        // Keycloak is required even though none of the tests below call an authenticated
        // endpoint or fetch a token. Shared singleton (see KeycloakTestContainer's Javadoc).
        private static final KeycloakTestContainer keycloak = KeycloakTestContainer.getInstance();

        @DynamicPropertySource
        static void keycloakProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak::issuerUri);
        }

        @AfterAll
        static void tearDown(@Autowired DataSource dataSource) {
                if (dataSource instanceof HikariDataSource hikariDataSource) {
                        hikariDataSource.close();
                }
        }

        @LocalServerPort
        private int port;

        @Autowired
        private UserProfileEntityRepository userProfileRepository;

        @Autowired
        private PendingRegistrationEntityRepository pendingRegistrationRepository;

        @MockitoBean
        private EmailService emailService;

        @MockitoBean
        private KeycloakAdminClient keycloakAdminClient;

        private WebTestClient webTestClient;

        @BeforeEach
        void setUp() {
                webTestClient = WebTestClient.bindToServer()
                                .baseUrl("http://localhost:" + port)
                                .build();

                // Clean up before each test
                pendingRegistrationRepository.deleteAll();
                userProfileRepository.deleteAll();
        }

        @Test
        void testSuccessfulRegistration() {
                SelfRegistrationRequest registration = createValidRegistration("test@example.com");

                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isCreated()
                                .expectBody()
                                .jsonPath("$.message")
                                .isEqualTo("If this email is not registered yet, a confirmation link has been sent.")
                                .jsonPath("$.email").isEqualTo("test@example.com");

                // Verify email was sent
                ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
                verify(emailService).sendConfirmationEmail(eq("test@example.com"), urlCaptor.capture());
                assertThat(urlCaptor.getValue()).startsWith("http://localhost:4200/confirm?token=");

                // Verify profile created but disabled
                var profile = userProfileRepository.findByEmail("test@example.com");
                assertThat(profile).isPresent();
                assertThat(profile.get().getEnabled()).isFalse();

                // Verify pending registration created
                var pending = pendingRegistrationRepository.findAll();
                assertThat(pending).hasSize(1);
                assertThat(pending.getFirst().getConfirmationToken()).isNotNull();
        }

        @Test
        void testSuccessfulConfirmation() {
                // First register
                SelfRegistrationRequest registration = createValidRegistration("confirm@example.com");

                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isCreated();

                // Get the confirmation token from DB
                var pending = pendingRegistrationRepository.findAll().getFirst();
                String token = pending.getConfirmationToken();

                // Mock Keycloak user creation
                String keycloakUserId = UUID.randomUUID().toString();
                when(keycloakAdminClient.createUserWithPasswordAction(
                                anyString(), anyString(), anyString(), anyString())).thenReturn(keycloakUserId);

                // Confirm registration
                webTestClient.get().uri("/api/public/confirm?token=" + token)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.message")
                                .isEqualTo("Email confirmed. Please check your email to set your password.")
                                .jsonPath("$.email").isEqualTo("confirm@example.com");

                // Verify profile is now enabled with Keycloak user ID
                var profile = userProfileRepository.findByEmail("confirm@example.com");
                assertThat(profile).isPresent();
                assertThat(profile.get().getEnabled()).isTrue();
                assertThat(profile.get().getUserId()).isEqualTo(keycloakUserId);

                // Verify pending registration deleted
                assertThat(pendingRegistrationRepository.findAll()).isEmpty();
        }

        @Test
        void testDuplicateEmailRegistration() {
                SelfRegistrationRequest registration = createValidRegistration("duplicate@example.com");

                // First registration succeeds
                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isCreated();

                // Second registration for the same email returns the SAME 201 response - the
                // endpoint must not reveal that the email is already registered (enumeration
                // prevention, see RegistrationService#register).
                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isCreated()
                                .expectBody()
                                .jsonPath("$.message")
                                .isEqualTo("If this email is not registered yet, a confirmation link has been sent.")
                                .jsonPath("$.email").isEqualTo("duplicate@example.com");

                // Only the first request actually created anything, and only it sent an email.
                assertThat(pendingRegistrationRepository.findAll()).hasSize(1);
                verify(emailService, times(1)).sendConfirmationEmail(eq("duplicate@example.com"), anyString());
        }

        @Test
        void testInvalidEmailFormat() {
                SelfRegistrationRequest registration = createValidRegistration("invalid-email");

                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void testInvalidConfirmationToken() {
                webTestClient.get().uri("/api/public/confirm?token=invalid-token")
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.detail").isEqualTo("Invalid confirmation token");
        }

        @Test
        void testExpiredConfirmationToken() {
                // First register
                SelfRegistrationRequest registration = createValidRegistration("expired@example.com");

                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isCreated();

                // Manually expire the token
                var pending = pendingRegistrationRepository.findAll().getFirst();
                pending.setTokenExpiry(Instant.now().minus(1, ChronoUnit.HOURS));
                pendingRegistrationRepository.save(pending);

                // Try to confirm with expired token
                webTestClient.get().uri("/api/public/confirm?token=" + pending.getConfirmationToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.detail").isEqualTo("Confirmation token has expired");
        }

        @Test
        void testMissingRequiredFields() {
                SelfRegistrationRequest registration = new SelfRegistrationRequest();
                // Email (the only @NotBlank field) is missing

                webTestClient.post().uri("/api/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(registration)
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        private SelfRegistrationRequest createValidRegistration(String email) {
                SelfRegistrationRequest dto = new SelfRegistrationRequest();
                dto.setEmail(email);
                dto.setFirstName("Test");
                dto.setLastName("User");
                dto.setGender(Gender.MALE);
                dto.setAge(25);
                return dto;
        }
}

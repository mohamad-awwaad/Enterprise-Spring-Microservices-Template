package com.example.profileservice;

import com.example.common.test.KeycloakTestContainer;
import com.example.profileservice.dto.UserProfileRequest;
import com.example.profileservice.dto.UserProfileResponse;
import com.example.profileservice.model.Gender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.idle-timeout=10000",
                "spring.datasource.hikari.max-lifetime=10000"
})
@Testcontainers
class ProfileIntegrationTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

        // Shared singleton (see KeycloakTestContainer's Javadoc) - started here instead of relying
        // on @Container/@Testcontainers so it is not restarted per test class.
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

        private WebTestClient webTestClient;

        @BeforeEach
        void setUp() {
                webTestClient = WebTestClient.bindToServer()
                                .baseUrl("http://localhost:" + port)
                                .build();
        }

        @Test
        void testProfileLifecycleWithKeycloakToken() {
                // 1. Get Token from the containerized Keycloak (hermetic - no dependency on a
                // locally running Keycloak instance). bff-client no longer allows the password
                // grant (directAccessGrantsEnabled=false); test-client is dedicated to tests.
                String accessToken = keycloak.passwordGrantToken("user", "password");
                assertThat(accessToken).isNotNull();

                // 2. Create Profile
                UserProfileRequest newProfile = UserProfileRequest.builder()
                                .firstName("Alice")
                                .lastName("Smith")
                                .email("alice@example.com")
                                .gender(Gender.FEMALE)
                                .age(30)
                                .build();

                webTestClient.post().uri("/api")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(newProfile)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody(UserProfileResponse.class)
                                .value(p -> {
                                        assert p != null;
                                        assertThat(p.getFirstName()).isEqualTo("Alice");
                                        assertThat(p.getUserId()).isNotNull();
                                });

                // 3. Get Profile
                webTestClient.get().uri("/api")
                                .header("Authorization", "Bearer " + accessToken)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody(UserProfileResponse.class)
                                .value(p -> {
                                        assert p != null;
                                        assertThat(p.getLastName()).isEqualTo("Smith");
                                });

                // 4. Delete Profile
                webTestClient.delete().uri("/api")
                                .header("Authorization", "Bearer " + accessToken)
                                .exchange()
                                .expectStatus().isNoContent();

                // 5. Verify Deletion
                webTestClient.get().uri("/api")
                                .header("Authorization", "Bearer " + accessToken)
                                .exchange()
                                .expectStatus().isNotFound();
        }
}

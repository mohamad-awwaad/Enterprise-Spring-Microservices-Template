package com.example.profileservice;

import com.example.profileservice.dto.UserProfileRequest;
import com.example.profileservice.dto.UserProfileResponse;
import com.example.profileservice.model.Gender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.AfterAll;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.idle-timeout=10000",
                "spring.datasource.hikari.max-lifetime=10000",
                "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Testcontainers
class ProfileIntegrationTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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
                // 1. Get Token from Keycloak
                String tokenUrl = "http://localhost:8080/realms/my-realm/protocol/openid-connect/token";

                RestClient restClient = RestClient.create();

                MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                formData.add("client_id", "bff-client");
                formData.add("client_secret", "mysecret");
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
                String accessToken = (String) tokenResponse.get("access_token");
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

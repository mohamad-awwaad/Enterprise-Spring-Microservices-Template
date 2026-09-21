package com.example.orderservice;

import com.example.common.test.KeycloakTestContainer;
import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.dto.OrderResponse;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrdersIntegrationTest {

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
    void testOrderLifecycleWithKeycloakToken() {
        // 1. Get Token from the containerized Keycloak (hermetic - no dependency on a locally
        // running Keycloak instance). bff-client no longer allows the password grant
        // (directAccessGrantsEnabled=false); test-client is dedicated to tests.
        String accessToken = keycloak.passwordGrantToken("user", "password");
        assertThat(accessToken).isNotNull();

        // 2. Create Order
        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setOrderNumber("ORD-999");

        webTestClient.post().uri("/api")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(orderRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .value(order -> {
                    assert order != null;
                    assertThat(order.getOrderNumber()).isEqualTo("ORD-999");
                    assertThat(order.getCreatedBy()).isNotNull();
                });

        // 3. Get Orders (Paginated)
        webTestClient.get().uri("/api")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isArray()
                .jsonPath("$.content[0].orderNumber").isEqualTo("ORD-999")
                .jsonPath("$.totalElements").value(total -> assertThat((Integer) total).isGreaterThanOrEqualTo(1));
    }
}

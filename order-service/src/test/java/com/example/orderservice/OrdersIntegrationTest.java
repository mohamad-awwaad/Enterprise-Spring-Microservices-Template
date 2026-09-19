package com.example.orderservice;

import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.dto.OrderResponse;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Testcontainers
class OrdersIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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

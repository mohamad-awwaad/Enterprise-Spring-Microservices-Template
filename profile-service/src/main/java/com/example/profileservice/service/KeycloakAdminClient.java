package com.example.profileservice.service;

import com.example.profileservice.dto.UserRegistrationDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class KeycloakAdminClient {

    private final RestClient restClient;

    @Value("${keycloak.admin-service.url}")
    private String adminServiceUrl;

    /**
     * {@code RestClient.Builder} is a prototype-scoped bean (a fresh, pre-configured builder
     * per injection point). Building the client once here - rather than calling
     * {@code restClientBuilder.build()} on every request - keeps Boot's auto-configured
     * builder (Micrometer observation/trace propagation, {@code spring.http.clients.*}
     * timeouts) instead of discarding it each time.
     */
    public KeycloakAdminClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public String createUser(UserRegistrationDTO dto, String bearerToken) {
        return restClient.post()
                .uri(adminServiceUrl + "/users")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .body(dto)
                .retrieve()
                .body(String.class);
    }

    public void deleteUser(String userId, String bearerToken) {
        restClient.delete()
                .uri(adminServiceUrl + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Creates a Keycloak user without password and triggers the UPDATE_PASSWORD action email.
     * Used for self-registration flow where user sets their own password via Keycloak email.
     *
     * @param email     User's email (also used as username)
     * @param username  Username
     * @param firstName First name
     * @param lastName  Last name
     * @return The created Keycloak user ID
     */
    public String createUserWithPasswordAction(String email, String username, String firstName, String lastName) {
        var request = java.util.Map.of(
                "email", email,
                "username", username,
                "firstName", firstName,
                "lastName", lastName,
                "sendPasswordEmail", true
        );

        return restClient.post()
                .uri(adminServiceUrl + "/users/register")
                .body(request)
                .retrieve()
                .body(String.class);
    }
}

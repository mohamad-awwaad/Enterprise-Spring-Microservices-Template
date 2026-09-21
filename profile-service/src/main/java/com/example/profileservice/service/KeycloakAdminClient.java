package com.example.profileservice.service;

import com.example.profileservice.dto.UserRegistrationDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final RestClient internalServiceRestClient;

    @Value("${keycloak.admin-service.url}")
    private String adminServiceUrl;

    /**
     * {@code RestClient.Builder} is a prototype-scoped bean (a fresh, pre-configured builder
     * per injection point). Building the client once here - rather than calling
     * {@code restClientBuilder.build()} on every request - keeps Boot's auto-configured
     * builder (Micrometer observation/trace propagation, {@code spring.http.clients.*}
     * timeouts) instead of discarding it each time.
     * <p>
     * {@code internalServiceRestClient} is a second client built from its own builder
     * injection point (each is a fresh prototype instance), with an
     * {@link OAuth2ClientHttpRequestInterceptor} added on top. That interceptor obtains
     * (and transparently refreshes) a client-credentials token for whichever client
     * registration the request is tagged with via
     * {@link RequestAttributeClientRegistrationIdResolver#clientRegistrationId(String)} and
     * attaches it as the Authorization header - used only by
     * {@link #createUserWithPasswordAction} to call the now-authenticated
     * {@code /api/users/register} endpoint as the "internal" service account.
     */
    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                                RestClient.Builder internalServiceRestClientBuilder,
                                OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = restClientBuilder.build();
        this.internalServiceRestClient = internalServiceRestClientBuilder
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager))
                .build();
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
     * <p>
     * {@code /api/users/register} now requires the {@code INTERNAL_SERVICE} realm role, so this
     * call goes through {@code internalServiceRestClient}, which attaches a client-credentials
     * token for the "internal" registration (client {@code internal-client}).
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

        return internalServiceRestClient.post()
                .uri(adminServiceUrl + "/users/register")
                .attributes(RequestAttributeClientRegistrationIdResolver.clientRegistrationId("internal"))
                .body(request)
                .retrieve()
                .body(String.class);
    }
}

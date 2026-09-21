package com.example.bff.config;

import com.example.common.test.KeycloakTestContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@TestConfiguration
public class TestConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        // Defining this bean directly (rather than relying on Boot's issuer-uri-based
        // auto-configuration) avoids the eager OIDC discovery call that auto-configuration would
        // otherwise make while the Spring context is still starting up; the explicit endpoints
        // below point at the shared containerized Keycloak (see KeycloakTestContainer), started
        // on demand by the time this bean method runs.
        String issuerUri = KeycloakTestContainer.getInstance().issuerUri();
        ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
                .clientId("bff-client")
                .clientSecret("mysecret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .scope("openid", "profile", "email")
                .authorizationUri(issuerUri + "/protocol/openid-connect/auth")
                .tokenUri(issuerUri + "/protocol/openid-connect/token")
                .userInfoUri(issuerUri + "/protocol/openid-connect/userinfo")
                .jwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                .userNameAttributeName("preferred_username")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }
}

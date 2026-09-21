package com.example.profileservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Service-specific security configuration.
 * <p>
 * The main SecurityFilterChain is auto-configured by common-security's
 * {@code ResourceServerSecurityAutoConfiguration}. Public endpoints are
 * configured via {@code security.resource-server.public-endpoints} property.
 * <p>
 * This class only provides service-specific beans like the OAuth2 client machinery used
 * to call keycloak-admin-service internally.
 * <p>
 * No longer defines a {@code PasswordEncoder} bean: it existed only to BCrypt-hash the
 * password on the self-registration DTO, and that field never actually got read back anywhere
 * (see {@code RegistrationService}) - the public registration flow no longer accepts a password
 * at all.
 */
@Configuration
public class SecurityConfig {

    /**
     * Backs the "internal" client registration's client-credentials tokens with an in-memory
     * store. There is no end-user principal for a service-to-service call, so a full session- or
     * repository-backed client store (as the BFF uses for user logins) would be overkill here.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * Only the client_credentials grant is registered (no authorization_code), which is why
     * adding spring-boot-starter-oauth2-client does not cause Boot to add an oauth2Login()
     * SecurityFilterChain: Boot's OAuth2ClientWebSecurityAutoConfiguration backs off via
     * {@code @ConditionalOnMissingBean(SecurityFilterChain.class)} once common-security's
     * resource-server chain is registered, and there is no authorization_code registration to
     * drive a login flow off of anyway. This manager is used directly by KeycloakAdminClient's
     * dedicated RestClient (via OAuth2ClientHttpRequestInterceptor) for the "internal"
     * registration only - it never sits on the request-handling filter chain.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);
        return authorizedClientManager;
    }
}

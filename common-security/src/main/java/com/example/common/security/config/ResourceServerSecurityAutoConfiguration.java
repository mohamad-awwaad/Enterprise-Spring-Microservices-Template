package com.example.common.security.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auto-configuration for OAuth2 Resource Server security.
 * <p>
 * Provides a default {@link SecurityFilterChain} with:
 * <ul>
 *   <li>CSRF disabled (stateless JWT-based auth)</li>
 *   <li>/actuator/health and /actuator/prometheus permitted</li>
 *   <li>Additional public endpoints configurable via properties</li>
 *   <li>All other requests require authentication</li>
 *   <li>OAuth2 Resource Server with Keycloak JWT converter</li>
 * </ul>
 * <p>
 * This auto-configuration is skipped if a {@link SecurityFilterChain} bean already exists,
 * allowing services to provide custom security configuration when needed.
 * <p>
 * Usage in application.properties:
 * <pre>
 * security.resource-server.public-endpoints=/api/public/**,/custom/endpoint
 * </pre>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpSecurity.class, JwtAuthenticationConverter.class})
@EnableConfigurationProperties(ResourceServerSecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain resourceServerSecurityFilterChain(
            HttpSecurity http,
            ResourceServerSecurityProperties properties) throws Exception {

        http
            /*
             * CSRF Protection Disabled:
             * Resource servers operate as stateless JWT-based services. They do not maintain
             * user sessions or use cookies for authentication. All requests are authenticated
             * via Bearer tokens in the Authorization header, making CSRF protection unnecessary.
             */
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> {
                // Always permit actuator health (incl. liveness/readiness probe groups) and prometheus endpoints
                authorize.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll();

                // Permit additional public endpoints from configuration
                for (String pattern : properties.getPublicEndpoints()) {
                    authorize.requestMatchers(pattern).permitAll();
                }

                // All other requests require authentication
                authorize.anyRequest().authenticated();
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter()))
            );

        return http.build();
    }
}

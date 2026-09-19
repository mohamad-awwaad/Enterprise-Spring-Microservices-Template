package com.example.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            /*
             * CSRF Protection Disabled:
             * The Gateway operates as a stateless JWT Resource Server. It does not maintain
             * user sessions or use cookies for authentication. All requests are authenticated
             * via Bearer tokens in the Authorization header.
             *
             * - Web clients: CSRF is handled by the BFF service, which manages sessions.
             * - Mobile clients: Use token-based auth directly, immune to CSRF by design.
             *
             * Therefore, CSRF protection at the Gateway layer is unnecessary and disabled.
             */
            .csrf(csrf -> csrf.disable())
            .authorizeExchange(exchanges -> exchanges
                // Allow public endpoints and Swagger UI resources
                // Pattern: /*/public/** matches service-prefixed public paths like:
                //   /profile/public/register, /profile/public/api-docs, /orders/public/api-docs
                // "/actuator/prometheus" is permitted so Prometheus can scrape metrics without a
                // token; a dedicated management.server.port (separate from the app port, not
                // internet-routable) is the production-grade alternative.
                .pathMatchers("/*/public/**", "/actuator/health", "/actuator/prometheus", "/webjars/swagger-ui/**", "/v3/api-docs/**", "/fallback").permitAll()
                // Require authentication for everything else
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        
        return http.build();
    }
}

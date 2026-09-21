package com.example.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Wires Spring Data JPA auditing ({@code @CreatedDate}/{@code @LastModifiedDate}/
 * {@code @CreatedBy}/{@code @LastModifiedBy} on {@link com.example.orderservice.model.OrderEntity})
 * to the resource server's authenticated principal.
 * <p>
 * The auditor is the JWT's subject ({@code sub} claim) - the same identity
 * {@code OrdersController}/{@code OrderService} already use to scope a user's orders
 * ({@code OrderRepository#findByCreatedBy}), so a created order's {@code createdBy} always
 * matches what the owning user is queried by.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.empty();
            }
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                return Optional.ofNullable(jwt.getSubject());
            }
            return Optional.empty();
        };
    }
}

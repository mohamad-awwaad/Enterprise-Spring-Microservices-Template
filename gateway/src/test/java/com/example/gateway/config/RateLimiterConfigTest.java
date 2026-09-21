package com.example.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RateLimiterConfig#userKeyResolver}: no Spring context or containers
 * needed, since the config class can be instantiated directly and its bean methods called like
 * plain factory methods.
 */
class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    private KeyResolver keyResolver() {
        // trustedProxyHops defaults to 0 (the @Value default) since this isn't a Spring context;
        // that yields the plain RemoteAddressResolver (TCP peer address, X-Forwarded-For ignored).
        RemoteAddressResolver remoteAddressResolver = config.remoteAddressResolver();
        return config.userKeyResolver(remoteAddressResolver);
    }

    @Test
    void resolvesAuthenticatedRequestsToTheirPrincipalName() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("alice");

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.just(principal));

        String key = keyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("user:alice");
    }

    @Test
    void fallsBackToRemoteAddressWhenUnauthenticated() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("203.0.113.5", 54321));
        when(exchange.getRequest()).thenReturn(request);

        String key = keyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("ip:203.0.113.5");
    }

    @Test
    void trustedProxyHopsGreaterThanZeroUsesXForwardedResolver() {
        ReflectionTestUtils.setField(config, "trustedProxyHops", 1);

        RemoteAddressResolver resolver = config.remoteAddressResolver();

        assertThat(resolver).isInstanceOf(
                org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver.class);
    }
}

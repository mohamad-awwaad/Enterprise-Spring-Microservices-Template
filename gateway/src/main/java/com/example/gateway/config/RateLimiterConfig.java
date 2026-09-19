package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Configuration for rate limiting key resolution.
 * <p>
 * Uses the authenticated user's ID (JWT subject) as the rate limit key.
 * Falls back to the caller's resolved remote address for unauthenticated requests.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Number of reverse proxies (load balancers, CDNs, etc.) trusted to sit in front of this
     * Gateway. {@code X-Forwarded-For} is a plain, client-supplied header: with no trusted
     * proxy in front (the default, 0), any caller can forge it to fabricate a rate-limit
     * identity (dodging its own limit) or frame another IP, so by default it is ignored
     * entirely in favor of the TCP peer address ({@link RemoteAddressResolver}'s default
     * behavior). Set this to the number of trusted proxies so only the hop(s) they legitimately
     * added are trusted, via {@link XForwardedRemoteAddressResolver#maxTrustedIndex(int)}.
     */
    @Value("${gateway.rate-limiter.trusted-proxy-hops:0}")
    private int trustedProxyHops;

    @Bean
    public RemoteAddressResolver remoteAddressResolver() {
        return trustedProxyHops > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)
                : new RemoteAddressResolver() {
                };
    }

    @Bean
    public KeyResolver userKeyResolver(RemoteAddressResolver remoteAddressResolver) {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.defer(() -> {
                    InetSocketAddress address = remoteAddressResolver.resolve(exchange);
                    String ip = Objects.requireNonNull(address, "unable to resolve remote address")
                            .getAddress().getHostAddress();
                    return Mono.just("ip:" + ip);
                }));
    }
}

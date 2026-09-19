package com.example.gateway.controller;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashSet;

/**
 * Fallback controller for circuit breaker.
 * Returns 503 Service Unavailable when downstream services are unavailable.
 */
@RestController
public class FallbackController {

    @RequestMapping(value = "/fallback", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ProblemDetail> fallback(ServerWebExchange exchange) {
        String path = resolveOriginalPath(exchange);

        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The downstream service is temporarily unavailable. Please try again later.");
        problemDetail.setTitle("Service Unavailable");
        problemDetail.setProperty("path", path);

        return Mono.just(problemDetail);
    }

    /**
     * The circuit breaker forwards here internally, so {@code exchange.getRequest().getPath()}
     * would just report "/fallback" itself, not the route the caller actually asked for. Spring
     * Cloud Gateway stashes the pre-forward request URL(s) under
     * {@code GATEWAY_ORIGINAL_REQUEST_URL_ATTR}; use the first one when present, falling back to
     * the current (i.e. "/fallback") path otherwise.
     */
    private static String resolveOriginalPath(ServerWebExchange exchange) {
        LinkedHashSet<URI> originalUris = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originalUris != null && !originalUris.isEmpty()) {
            return originalUris.iterator().next().getPath();
        }
        return exchange.getRequest().getPath().value();
    }
}

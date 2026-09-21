package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Routes are declared in {@code application.properties}
 * ({@code spring.cloud.gateway.server.webflux.routes[*]}), so every route goes through the same
 * JWT relay, rate limiter and circuit breaker filters. Do not add programmatic routes here.
 */
@SpringBootApplication
public class GatewayApplication {

    static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

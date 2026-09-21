# Development Roadmap

This document outlines the coding tasks, features, and configurations to be implemented and tested in the development environment.

## 1. Resilience & Reliability

- [x] **Circuit Breakers:**
    - Integrated **Resilience4j** at Gateway and BFF. Gateway returns 503 with fallback response when downstream services fail. Configurable sliding window (10 calls), 50% failure threshold, 10s wait in open state.
- [x] **Rate Limiting:**
    - Configured `RequestRateLimiter` at the **Gateway** using Redis with Token Bucket algorithm (10 req/s sustained, 20 burst).

## 2. Observability & Monitoring

- [x] **Distributed Tracing:**
    - Added **Micrometer Tracing** with Brave bridge and Zipkin exporter to propagate `TraceId` across BFF → Gateway → Microservices. Zipkin UI at http://localhost:9411.
- [x] **Centralized Logging:**
    - Added structured logging with trace correlation (traceId, spanId) via Logback.
    - Three profiles: `default` (console), `json` (structured JSON), `loki` (push to Loki).
    - Added Loki for log aggregation and Grafana for visualization.
    - Enable with: `--spring.profiles.active=loki` or `SPRING_PROFILES_ACTIVE=loki`.
- [x] **Grafana Dashboards:**
    - Added Prometheus for metrics collection (scrapes all services at `/actuator/prometheus` on their management ports).
    - Added `micrometer-registry-prometheus` dependency to all services.
    - Provisioned Spring Boot Services dashboard with HTTP requests, JVM metrics, circuit breakers, and system metrics.
    - Access Grafana at http://localhost:3000 (admin/admin), Prometheus at http://localhost:9090.
- [x] **Health Monitoring (Actuator):**
    - Added Spring Boot Actuator to all services and exposed health/info/metrics endpoints.
    - Actuator is served on a separate management port per service (service port + 1000) with liveness/readiness probe groups.

## 3. Security Features

- [x] **CORS Configuration:**
    - Explicitly configured at the **BFF** to allow frontend access with credentials.
- [x] **JWT Validation Enhancement:**
    - `JwtUtils` updated to validate signature (via JWKS) and expiration.
- [x] **Single Sign-Out (SLO):**
    - Implemented unified logout flow that clears Redis session, cookies, and redirects to Keycloak end-session endpoint.

## 4. API Quality & Documentation

- [x] **Global Error Handling:**
    - Implemented `GlobalExceptionHandler` in `common-web` providing RFC 9457 Problem Details for consistent error responses.
- [x] **Input Validation:**
    - Added `spring-boot-starter-validation` and applied `@Valid` annotations to all DTOs and Controllers.
- [x] **API Documentation:**
    - Added **SpringDoc OpenAPI** with Gateway aggregation and conditional security (public in dev, disabled in prod).

## 5. Code Quality & Standards

- [x] **Standardize Constants:**
    - Centralized `SecurityConstants` and `SessionConstants` in `common-core`.
- [x] **JPA Best Practices:**
    - Refactored entities (`*Entity`) to use Hibernate-safe `equals/hashCode` and removed `@Data`.
- [x] **Service Layer Abstraction:**
    - Moved business logic from Controllers to dedicated Service classes.

## 6. Testing

- [x] **Registration Flow Tests:**
    - Added integration tests covering registration, confirmation, and validation scenarios.

## 7. Angular Frontend

- [x] **Registration Page:**
    - Implemented registration form with validation and error handling.
- [x] **Email Confirmation Page:**
    - Implemented token validation and confirmation UI.
- [x] **Login Integration:**
    - Integrated registration flow with login page.

## 8. Build, Tests & CI

- [x] **Spring Boot 4.1 upgrade:**
    - All services on Spring Boot 4.1.x / Spring Cloud 2025.1.x; gateway on Spring Cloud Gateway Server WebFlux.
- [x] **Database migrations:**
    - Flyway for `profile-service` and `order-service`, Hibernate only validates the schema.
- [x] **Docker:**
    - One multi-stage `Dockerfile` for the Java services, nginx image for the Angular UI, single `compose.yaml` (infra by default, full stack with `--profile apps`).
- [x] **CI:**
    - GitLab CI pipeline (build, frontend, Testcontainers tests, image build), Maven wrapper, Renovate.
- [x] **Hermetic integration tests:**
    - `common-test` module with Keycloak (realm import) and Redis Testcontainers; no live Keycloak needed. Gateway and keycloak-admin-service have tests.
- [x] **Frontend tests:**
    - Vitest component/service specs and Playwright end-to-end tests (login, profile, orders, logout).

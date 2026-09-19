# Production Readiness Checklist

This document outlines the specific infrastructure configurations and policy audits required before deploying the application to a live production environment.

## 1. Environment Configuration

- [ ] **Secrets Management:**
    - Transition from hardcoded values in `application.properties` to Environment Variables or a Secrets Manager (e.g., Vault, AWS Secrets Manager).
    - Ensure `docker-compose.yml` uses `.env` files (excluded from Git) for credentials.
- [ ] **BFF Session Signing Key:**
    - Set `BFF_JWT_SIGNING_KEY` before starting with the `prod` profile - `JwtUtils` refuses to
      start under `prod` without one (fail-fast), and otherwise falls back to an ephemeral
      in-memory key that does not survive a restart (fine for dev, never for prod).
    - Generate with: `openssl genpkey -algorithm RSA -out private_key.pem -pkeyopt rsa_keygen_bits:2048`
- [ ] **Redis AUTH & TLS:**
    - The BFF's `BffSession` records (access/refresh/ID token values, keyed by `bff:session:<jti>`)
      and the `JSESSIONID` Spring Session both live in Redis - anyone who can read that Redis
      instance can read live user tokens. Require authentication (`requirepass` / Redis ACLs, or
      a managed Redis's IAM/AUTH mechanism) and enable TLS for connections to Redis, especially
      when it is reachable outside the local Docker network.
    - Configure `spring.data.redis.password` (and `spring.data.redis.ssl.enabled=true` where
      supported) to match.
- [ ] **Encryption at Rest for Session Data (optional, defense in depth):**
    - `BffSession` is stored as plain JSON in Redis (see `docs/dual_session_strategy.md`) - no
      longer JDK-serialized, but also not encrypted. For an additional layer beyond Redis AUTH/TLS
      and host/disk-level encryption, consider encrypting the serialized value before it is
      written (e.g. a custom `RedisSerializer` wrapping `JacksonJsonRedisSerializer` with
      AES-GCM, keyed from a secret separate from `BFF_JWT_SIGNING_KEY`). Not implemented in this
      template; evaluate based on your threat model and compliance requirements.

## 2. Architectural Audits

- [ ] **Session Management Strategy:**
    - Evaluate migrating from custom JWT-in-cookie logic to full **Spring Session Redis** for robust, scalable session lifecycle management if the current solution proves insufficient for production loads.
- [ ] **BFF Error Handling:**
    - Verify that `BffController`'s error suppression logic (hiding details in `prod` profile) aligns with corporate security policies.

## 3. Email Configuration

- [ ] **SMTP Server:**
    - Configure a real SMTP server for email delivery (registration confirmation, password reset).
    - Current implementation uses `LoggingEmailService` which only logs emails.
    - `LoggingEmailService` is annotated `@Profile("!prod")`, so it is not registered when the
      `prod` profile is active. Provide a real `EmailService` bean (e.g. `SmtpEmailService`)
      before deploying with `prod` - otherwise the app fails to start (no `EmailService` bean
      for `RegistrationService` to inject), which is intentional fail-fast behavior rather than
      silently logging confirmation tokens instead of emailing them.
    - Replace with production `SmtpEmailService` implementation.
    - Required properties:
      ```properties
      spring.mail.host=smtp.example.com
      spring.mail.port=587
      spring.mail.username=<username>
      spring.mail.password=<password>
      spring.mail.properties.mail.smtp.auth=true
      spring.mail.properties.mail.smtp.starttls.enable=true
      ```

- [ ] **Email Templates:**
    - Create professional HTML email templates for registration confirmation.
    - Ensure emails include proper branding and clear call-to-action.

## 4. Distributed Tracing

- [ ] **Sampling Rate:**
    - Reduce `management.tracing.sampling.probability` from `1.0` to `0.1` or lower for production traffic.
- [ ] **Zipkin Storage:**
    - Configure persistent storage (Elasticsearch, Cassandra, or MySQL) instead of in-memory.
    - Consider managed tracing services (AWS X-Ray, GCP Cloud Trace, Jaeger).
- [ ] **Trace Retention:**
    - Define trace retention policy based on compliance requirements.

## 5. Keycloak Email Configuration

- [ ] **Keycloak SMTP Settings:**
    - Configure Keycloak realm SMTP settings for password reset emails.
    - Realm Settings → Email → Configure SMTP server.
    - This is required for the "Set Password" email sent after registration confirmation.

## 6. Frontend Deployment

- [ ] **Proxy Configuration:**
    - Development uses `proxy.conf.json` to forward `/bff` requests to `http://localhost:8081`.
    - For production, replace with proper reverse proxy (nginx, Apache, or cloud load balancer):
      ```nginx
      location /bff {
          proxy_pass http://bff-service:8081;
          proxy_set_header Host $host;
          proxy_set_header X-Real-IP $remote_addr;
          proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
          proxy_set_header X-Forwarded-Proto $scheme;
      }
      ```
- [ ] **Static Assets:**
    - Build Angular app with `ng build --configuration production`.
    - Serve static files via nginx or CDN.
- [ ] **Environment Configuration:**
    - Configure `environment.prod.ts` with production API URLs.
    - Ensure `bff.frontend.url` in BFF properties matches production frontend URL.

## 7. Circuit Breaker Tuning

- [ ] **Threshold Configuration:**
    - Review `resilience4j.circuitbreaker.configs.default.failureRateThreshold` (currently 50%).
    - Adjust `slidingWindowSize` based on expected traffic patterns.
    - Set appropriate `waitDurationInOpenState` for your SLAs.
- [ ] **Timeout Configuration:**
    - Tune `resilience4j.timelimiter.configs.default.timeoutDuration` based on downstream service latencies.

## 8. Rate Limiting

- [ ] **Rate Limit Tuning:**
    - Review `replenishRate` and `burstCapacity` values per route.
    - Consider different limits for authenticated vs. public endpoints.
- [ ] **Redis High Availability:**
    - Configure Redis Sentinel or Redis Cluster for rate limiter state.

## 9. Centralized Logging

- [ ] **Enable Loki Profile:**
    - Set `SPRING_PROFILES_ACTIVE=loki` or `--spring.profiles.active=loki` for all services.
    - Configure `LOKI_URL` environment variable if not using default `http://localhost:3100`.
- [ ] **Loki Persistence:**
    - Configure Loki with persistent storage (S3, GCS, or filesystem).
    - Default in-memory storage is not suitable for production.
- [ ] **Log Retention:**
    - Define log retention policy in Loki configuration.
    - Consider compliance requirements for log retention period.
- [ ] **Grafana Security:**
    - Change default admin password (`admin/admin`).
    - Configure authentication (OAuth, LDAP) for production access.
    - Set up role-based access control for dashboards.

## 10. Network Security & Encryption

- [ ] **Internal Encryption (mTLS):**
    - Secure service-to-service communication (e.g., BFF to Gateway, Gateway to Microservices) using HTTPS or Mutual TLS (mTLS).
    - In modern environments (Kubernetes), this is typically handled by a Service Mesh (Istio, Linkerd) without requiring application-level changes.
    - If implementing at the application level, ensure proper certificate management and rotation policies are in place.
- [ ] **Redis Network Isolation:**
    - Redis holds live session data (see the AUTH/TLS item under Environment Configuration above)
      - it should sit on a private network segment reachable only by the BFF (and Spring Session
      consumers), never exposed to the public internet or to services that don't need it.
    - Restrict access with security groups/network policies in addition to Redis AUTH.
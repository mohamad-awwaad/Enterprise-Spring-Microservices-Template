package com.example.bff.controller;

import com.example.bff.service.SessionRedisService;
import com.example.bff.session.BffSession;
import com.example.bff.util.JwtUtils;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link BffController#proxyRequest} fails fast with a 503 ProblemDetail when
 * the "gateway" circuit breaker is OPEN, instead of attempting (and hanging on) a real call.
 * <p>
 * Constructed directly with Mockito (no Spring context needed): the controller's constructor
 * builds its own {@code RestClient} from the given builder and looks up the "gateway" breaker
 * from the given registry, so a plain {@link CircuitBreakerRegistry} is enough to drive it.
 */
class BffControllerCircuitBreakerTest {

    private BffController controller;
    private CircuitBreaker gatewayCircuitBreaker;
    private SessionRedisService sessionService;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        OAuth2AuthorizedClientService clientService = mock(OAuth2AuthorizedClientService.class);
        sessionService = mock(SessionRedisService.class);
        jwtUtils = mock(JwtUtils.class);
        Environment env = mock(Environment.class);

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        controller = new BffController(clientService, sessionService, jwtUtils, RestClient.builder(), registry, env);
        gatewayCircuitBreaker = registry.circuitBreaker("gateway");

        // Never actually dialed when the breaker is open, but proxyRequest() builds the
        // target URI before reaching the breaker, so it must be a valid base URL.
        ReflectionTestUtils.setField(controller, "gatewayUrl", "http://localhost:1");
    }

    @Test
    void proxyRequestReturns503ProblemDetailWhenCircuitIsOpen() {
        // Force the breaker open, simulating repeated 5xx/connection/timeout failures.
        gatewayCircuitBreaker.transitionToOpenState();

        String sessionJwt = "session.jwt.token";
        when(jwtUtils.extractJti(sessionJwt)).thenReturn("jti");
        when(sessionService.load("jti")).thenReturn(sessionWithAccessToken("valid-token"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/bff/api/orders");

        ResponseEntity<?> response = controller.proxyRequest(request, sessionJwt, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail problemDetail = (ProblemDetail) response.getBody();
        assertThat(problemDetail.getTitle()).isEqualTo("Service Unavailable");
        assertThat(problemDetail.getDetail()).isEqualTo("Gateway is temporarily unavailable");
    }

    private static BffSession sessionWithAccessToken(String tokenValue) {
        return new BffSession(
                "user", tokenValue, Instant.now().plusSeconds(300), "refresh-token", "id-token", Set.of("openid"));
    }
}

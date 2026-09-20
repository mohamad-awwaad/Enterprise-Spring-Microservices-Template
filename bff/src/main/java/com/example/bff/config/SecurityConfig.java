package com.example.bff.config;

import com.example.bff.filter.CsrfCookieFilter;
import com.example.bff.filter.TokenRefreshFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for the BFF service.
 * <p>
 * Key behaviors:
 * <ul>
 *   <li>OAuth2 login with Keycloak as the identity provider</li>
 *   <li>Custom authentication entry point that returns 401 for AJAX requests
 *       (allowing Angular to detect auth state) and redirects for browser navigation</li>
 *   <li>CORS configured to allow the Angular frontend with credentials</li>
 *   <li>CSRF protection enabled in all profiles using Spring Security's documented SPA recipe
 *       (readable cookie + plain request handler + a filter that forces the cookie to be
 *       written); a dev-only "disabled" branch is a template trap that hides CSRF bugs until
 *       production</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final TokenRefreshFilter tokenRefreshFilter;
    private final CsrfCookieFilter csrfCookieFilter;

    @org.springframework.beans.factory.annotation.Value("${bff.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @org.springframework.beans.factory.annotation.Value("${bff.cors.max-age-seconds}")
    private long corsMaxAgeSeconds;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .addFilterBefore(tokenRefreshFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                /*
                 * We permit "/api/**" because these endpoints are protected manually
                 * by the BffController and TokenRefreshFilter using the custom
                 * "BFF_SESSION" cookie and Redis lookup.
                 *
                 * This bypasses the standard JSESSIONID-based SecurityContext
                 * for these specific proxy endpoints.
                 *
                 * "/actuator/prometheus" is permitted so Prometheus can scrape metrics without
                 * a token; exposing it on the same port/filter chain as the app is convenient
                 * for a template. A dedicated management.server.port (separate from the app
                 * port, not internet-routable) is the production-grade alternative.
                 */
                .requestMatchers("/bff/login", "/bff/logout", "/bff/public/**", "/login/**", "/oauth2/**", "/bff/api/**", "/error", "/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/bff/login/success", true)
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint())
            )
            /*
             * CSRF Protection - Spring Security's documented SPA recipe, enabled in ALL
             * profiles (a dev-only "disabled" branch hides CSRF bugs until production):
             * - CookieCsrfTokenRepository.withHttpOnlyFalse(): the token must be readable by
             *   Angular's XSRF interceptor so it can echo it back as X-XSRF-TOKEN.
             * - CsrfTokenRequestAttributeHandler (not the default XOR handler): the default
             *   handler expects the *raw* token it wrote server-side back from the client, but
             *   also BREAKS non-Ajax/multipart form submits when combined with a deferred
             *   token; since the cookie itself is already unmasked (HttpOnly=false), the extra
             *   XOR masking adds nothing here and only causes the raw cookie value Angular
             *   sends back to be rejected.
             * - CsrfCookieFilter (after CsrfFilter): forces the deferred token to resolve on
             *   every request so the cookie is actually written (see its Javadoc).
             * - Public endpoints carry no session cookie, so CSRF doesn't apply there; the
             *   OAuth2 login endpoints are handled by Spring Security itself before any CSRF
             *   token would exist.
             */
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/bff/public/**", "/bff/login", "/login/**", "/oauth2/**")
            )
            .addFilterAfter(csrfCookieFilter, CsrfFilter.class);

        return http.build();
    }

    /**
     * Creates a delegating authentication entry point that handles unauthenticated requests differently
     * based on the request type.
     * <p>
     * Problem: By default, Spring Security redirects ALL unauthenticated requests to the OAuth2 login.
     * This breaks Angular's ability to check authentication state via /bff/user because the HTTP call
     * follows the redirect and gets stuck or returns HTML instead of a clean 401.
     * <p>
     * Solution: Return 401 for API/AJAX requests so the frontend can handle auth state properly,
     * while still redirecting browser navigation to the login page.
     * <p>
     * Matching order (first match wins):
     * <ol>
     *   <li>X-Requested-With: XMLHttpRequest header → 401 (standard AJAX marker)</li>
     *   <li>Accept: application/json header → 401 (API request)</li>
     *   <li>/bff/user endpoint specifically → 401 (auth check endpoint)</li>
     *   <li>Everything else → Redirect to OAuth2 login (browser navigation)</li>
     * </ol>
     */
    /*
     * Suppressed deprecation: The LinkedHashMap constructor for DelegatingAuthenticationEntryPoint
     * is deprecated in favor of a new constructor using RequestMatcherEntry. However, that class
     * is not yet available in the current Spring Security version. This suppression can be removed
     * once Spring Security provides the new API and we migrate to it.
     */
    @SuppressWarnings("deprecation")
    private AuthenticationEntryPoint authenticationEntryPoint() {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();

        // Return 401 for requests with X-Requested-With header (AJAX)
        entryPoints.put(
            new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest"),
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
        );

        // Return 401 for requests expecting JSON
        entryPoints.put(
            new RequestHeaderRequestMatcher("Accept", "application/json"),
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
        );

        // Return 401 for /bff/user endpoint when it's an API call (not browser navigation)
        // This allows Angular to check auth state via fetch() while still supporting
        // direct browser navigation to /bff/user (which should redirect to login)
        entryPoints.put(
            request -> request.getRequestURI().equals("/bff/user")
                && request.getHeader("Accept") != null
                && request.getHeader("Accept").contains("application/json"),
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
        );

        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        // Default: redirect to OAuth2 login for browser navigation
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keycloak"));

        return entryPoint;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Use injected origins from application.properties
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Requested-With"));
        // Critical: Must be true to allow the browser to send the BFF_SESSION cookie
        configuration.setAllowCredentials(true);
        // How long the browser should cache the preflight response
        configuration.setMaxAge(corsMaxAgeSeconds);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
package com.example.bff.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the CSRF cookie to be written on every response, as documented in Spring Security's
 * SPA CSRF recipe.
 * <p>
 * Spring Security 6+/7 defers generating/loading the {@link CsrfToken} until something actually
 * reads it. With a plain {@code CookieCsrfTokenRepository} that means the XSRF-TOKEN cookie is
 * never written unless a request happens to trigger that read - so Angular never receives a
 * token to echo back. Placing this filter {@code after(CsrfFilter.class)} and calling
 * {@link CsrfToken#getToken()} on the request attribute {@code CsrfFilter} already resolved
 * forces that generation/lookup on every request, guaranteeing the cookie is (re)written.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Triggers the deferred token to actually resolve, which is what makes
            // CookieCsrfTokenRepository write the XSRF-TOKEN cookie on the response.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}

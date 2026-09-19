package com.example.common.web.exception;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link GlobalExceptionHandler} through a real (standalone) Spring MVC dispatch
 * against {@link ThrowingController}, instead of calling its {@code @ExceptionHandler} methods
 * directly - that's the only way to prove exceptions thrown during request processing (not just
 * exceptions handed to the handler methods in isolation) resolve to the right status.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(Profiles.of("prod", "production"))).thenReturn(false);

        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(env))
                .build();
    }

    @Test
    void validationFailureReturns400WithErrorsMap() throws Exception {
        mockMvc.perform(post("/throw/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Input Validation Error"))
                .andExpect(jsonPath("$.detail").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").value("Name is required"));
    }

    @Test
    void responseStatusExceptionReturns409WithReasonPhraseTitle() throws Exception {
        // Title should be the plain reason phrase ("Conflict"), not HttpStatusCode#toString()
        // ("409 CONFLICT") as it used to be.
        mockMvc.perform(get("/throw/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Profile already exists"));
    }

    @Test
    void unsupportedHttpMethodReturns405() throws Exception {
        // "/throw/conflict" is only mapped for GET.
        mockMvc.perform(post("/throw/conflict"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/throw/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accessDeniedExceptionPropagatesInsteadOfBecoming500() {
        // Standalone MockMvc has no Spring Security filter chain to translate this into a 403,
        // so GlobalExceptionHandler#rethrowSecurityException rethrowing it surfaces here as a
        // ServletException wrapping the original AccessDeniedException - proof it is NOT being
        // swallowed by the generic Exception.class handler into a 500.
        ServletException thrown = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/throw/access-denied")));
        assertThat(thrown.getCause()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void genericExceptionReturns500WithExceptionPropertyWhenNotProduction() throws Exception {
        mockMvc.perform(get("/throw/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.exception").value("IllegalStateException"));
    }
}

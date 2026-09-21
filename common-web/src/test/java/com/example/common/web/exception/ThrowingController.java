package com.example.common.web.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal controller used only by {@link GlobalExceptionHandlerTest} to trigger each exception
 * path through a real (standalone) MVC dispatch - {@link GlobalExceptionHandler} itself has no
 * endpoints of its own that could otherwise exercise these paths.
 */
@RestController
class ThrowingController {

    @PostMapping("/throw/validate")
    public String validate(@Valid @RequestBody ValidatedRequest request) {
        return request.getName();
    }

    @GetMapping("/throw/conflict")
    public String conflict() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Profile already exists");
    }

    @GetMapping("/throw/access-denied")
    public String accessDenied() {
        throw new AccessDeniedException("Not allowed");
    }

    @GetMapping("/throw/generic")
    public String generic() {
        throw new IllegalStateException("Something broke");
    }

    // Plain getter/setter (no Lombok) so Jackson can bind it - this module has no existing
    // Lombok usage/annotation-processor wiring to piggyback on.
    static class ValidatedRequest {
        @NotBlank(message = "Name is required")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}

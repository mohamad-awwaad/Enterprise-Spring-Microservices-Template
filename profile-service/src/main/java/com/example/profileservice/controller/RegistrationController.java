package com.example.profileservice.controller;

import com.example.profileservice.dto.SelfRegistrationRequest;
import com.example.profileservice.model.UserProfileEntity;
import com.example.profileservice.service.RegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public endpoints for user self-registration.
 * <p>
 * <b>Security Design:</b>
 * <ul>
 *   <li>These endpoints are explicitly permitted in SecurityConfig without JWT validation</li>
 *   <li>Rate limiting should be applied at the gateway/infrastructure level to prevent abuse</li>
 *   <li>Registration creates a disabled profile; user cannot authenticate until email is confirmed</li>
 *   <li>Keycloak user is only created AFTER email confirmation to prevent spam accounts</li>
 *   <li>The user's real password is only ever set directly with Keycloak, via its own
 *       "Set Password" email sent after confirmation - this service never sees or stores one</li>
 *   <li>{@code /register} always answers 201 with the same generic message, whether or not the
 *       email was already registered, so the endpoint cannot be used to enumerate accounts
 *       (see {@link RegistrationService#register})</li>
 *   <li>Confirmation tokens are UUID v4 (122 bits of entropy), expire after configurable period</li>
 * </ul>
 * <p>
 * <b>Flow:</b>
 * <ol>
 *   <li>POST /register - Creates disabled profile + pending registration, sends confirmation email</li>
 *   <li>GET /confirm?token=xxx - Validates token, creates Keycloak user, enables profile</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    /**
     * Registers a new user, or silently no-ops if the email is already registered.
     * <p>
     * Always returns 201 with the same response shape - see
     * {@link RegistrationService#register} for why the response cannot depend on whether the
     * email already had an account.
     *
     * @param request Registration data (email + profile fields, no username/password)
     * @return Generic success message with instructions
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody SelfRegistrationRequest request) {
        registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "If this email is not registered yet, a confirmation link has been sent.",
                        "email", request.getEmail()
                ));
    }

    /**
     * Confirms registration using the token from confirmation email.
     * Creates the Keycloak user and triggers password setup email.
     *
     * @param token Confirmation token from email
     * @return Success message with next steps
     */
    @GetMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestParam String token) {
        UserProfileEntity profile = registrationService.confirm(token);
        return ResponseEntity.ok(Map.of(
                "message", "Email confirmed. Please check your email to set your password.",
                "email", profile.getEmail()
        ));
    }
}
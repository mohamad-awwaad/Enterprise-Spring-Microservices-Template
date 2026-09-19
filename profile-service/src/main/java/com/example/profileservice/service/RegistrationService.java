package com.example.profileservice.service;

import com.example.profileservice.dto.SelfRegistrationRequest;
import com.example.profileservice.model.PendingRegistrationEntity;
import com.example.profileservice.model.UserProfileEntity;
import com.example.profileservice.repository.PendingRegistrationEntityRepository;
import com.example.profileservice.repository.UserProfileEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Handles user self-registration flow with email confirmation.
 * <p>
 * <b>Registration Flow:</b>
 * <ol>
 *   <li>User submits registration form (email + profile data, no password)</li>
 *   <li>{@link #register} creates disabled UserProfileEntity + PendingRegistrationEntity</li>
 *   <li>Confirmation email sent with unique token (UUID v4)</li>
 *   <li>User clicks confirmation link</li>
 *   <li>{@link #confirm} validates token expiry, creates Keycloak user via admin API</li>
 *   <li>Keycloak sends "Set Password" email (UPDATE_PASSWORD required action)</li>
 *   <li>UserProfileEntity enabled, PendingRegistrationEntity deleted</li>
 * </ol>
 * <p>
 * <b>Security Design Decisions:</b>
 * <ul>
 *   <li><b>Keycloak user created AFTER confirmation:</b> Prevents spam/bot accounts in IdP.
 *       Unconfirmed users exist only in our DB, not in Keycloak.</li>
 *   <li><b>Password only ever set via Keycloak:</b> the user's real password is set directly
 *       with Keycloak, via the "Set Password" email it sends after confirmation. This service
 *       never collects, sees, or stores a password for the self-registration flow.</li>
 *   <li><b>Profile disabled until confirmed:</b> Even if someone bypasses email verification,
 *       the profile.enabled=false flag can be checked in business logic.</li>
 *   <li><b>Token expiry:</b> Configurable via app.registration.token-expiry-hours (default 24h).
 *       Expired tokens are rejected; users must re-register.</li>
 *   <li><b>No email enumeration:</b> {@link #register} returns the exact same outcome to the
 *       caller whether the email was free, already registered, or lost a race between the two -
 *       see its Javadoc.</li>
 * </ul>
 * <p>
 * <b>TODO for production:</b>
 * <ul>
 *   <li>Implement cleanup job to delete expired PendingRegistrations and their disabled profiles</li>
 *   <li>Add rate limiting to prevent registration spam</li>
 *   <li>Consider CAPTCHA integration for public registration endpoint</li>
 * </ul>
 *
 * @see PendingRegistrationEntity
 * @see KeycloakAdminClient
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

    private final UserProfileEntityRepository userProfileRepository;
    private final PendingRegistrationEntityRepository pendingRegistrationRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final EmailService emailService;
    private final RegistrationPersistenceService registrationPersistenceService;

    @Value("${app.registration.token-expiry-hours:24}")
    private int tokenExpiryHours;

    @Value("${app.registration.confirmation-base-url}")
    private String confirmationBaseUrl;

    /**
     * Registers a new user by creating a disabled profile and pending registration, or silently
     * no-ops if the email is already registered.
     * <p>
     * <b>Why no-op instead of erroring (enumeration prevention):</b> whether a given email
     * already has an account is itself sensitive information - confirming it lets an attacker
     * build a list of valid addresses to target with credential stuffing or password-reset abuse.
     * The old behavior (409 Conflict "Email already registered") leaked exactly that. This method
     * - and {@code RegistrationController#register} - now return the identical 201 response
     * regardless of outcome: nothing is created and no email is sent for a duplicate, but the
     * caller cannot tell the difference from a fresh registration.
     * <p>
     * <b>Race handling:</b> the upfront {@link UserProfileEntityRepository#findByEmail} check
     * handles the common case cheaply, but two concurrent requests for the same address could
     * both pass it. The database's unique constraint on {@code user_profiles.email} is the real
     * guard - the actual insert happens in {@link RegistrationPersistenceService#persist}, in its
     * own transaction, specifically so a {@link DataIntegrityViolationException} from that race
     * can be caught here without leaving this method's own (non-existent) transaction in a
     * rollback-only state.
     *
     * @param request Registration data
     */
    public void register(SelfRegistrationRequest request) {
        String email = request.getEmail();

        if (userProfileRepository.findByEmail(email).isPresent()) {
            log.info("Registration requested for already-registered email {}; ignoring (no email sent).", email);
            return;
        }

        String token = UUID.randomUUID().toString();
        Instant tokenExpiry = Instant.now().plus(tokenExpiryHours, ChronoUnit.HOURS);

        try {
            registrationPersistenceService.persist(request, token, tokenExpiry);
        } catch (DataIntegrityViolationException e) {
            // Lost the race described above: another request for this email committed between
            // the findByEmail check and this insert.
            log.info("Registration race detected for already-registered email {}; ignoring (no email sent).", email);
            return;
        }

        String confirmationUrl = confirmationBaseUrl + "?token=" + token;
        emailService.sendConfirmationEmail(email, confirmationUrl);

        log.info("User registered with email {}. Confirmation pending.", email);
    }

    /**
     * Confirms registration by validating token, creating Keycloak user,
     * and enabling the profile.
     *
     * @param token Confirmation token from email
     * @return The confirmed and enabled user profile
     */
    @Transactional
    public UserProfileEntity confirm(String token) {
        PendingRegistrationEntity pending = pendingRegistrationRepository.findByConfirmationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid confirmation token"));

        // Check expiry
        if (Instant.now().isAfter(pending.getTokenExpiry())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirmation token has expired");
        }

        UserProfileEntity profile = pending.getUserProfile();

        // Create Keycloak user and trigger password email
        try {
            String keycloakUserId = keycloakAdminClient.createUserWithPasswordAction(
                    profile.getEmail(),
                    profile.getEmail(), // username = email
                    profile.getFirstName(),
                    profile.getLastName()
            );

            // Update profile with Keycloak user ID and enable
            profile.setUserId(keycloakUserId);
            profile.setEnabled(true);
            userProfileRepository.save(profile);

            // Delete pending registration
            pendingRegistrationRepository.delete(pending);

            log.info("User {} confirmed and enabled. Keycloak user created: {}", profile.getEmail(), keycloakUserId);

            return profile;
        } catch (Exception e) {
            log.error("Failed to create Keycloak user for {}: {}", profile.getEmail(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to complete registration");
        }
    }
}

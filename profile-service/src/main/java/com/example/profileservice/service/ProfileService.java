package com.example.profileservice.service;

import com.example.profileservice.dto.UserProfileRequest;
import com.example.profileservice.dto.UserProfileResponse;
import com.example.profileservice.dto.UserRegistrationDTO;
import com.example.profileservice.model.UserProfileEntity;
import com.example.profileservice.repository.UserProfileEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileService {

    private final UserProfileEntityRepository userProfileRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String userId) {
        UserProfileEntity entity = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
        return UserProfileResponse.fromEntity(entity);
    }

    /**
     * Creates a profile for an authenticated user.
     * <p>
     * The {@code enabled} field defaults to {@code true} for profiles created through
     * this authenticated API endpoint. This differs from self-registration where
     * {@code enabled} starts as {@code false} until email confirmation.
     * <p>
     * <b>Why null check for enabled?</b>
     * Jackson deserialization may leave {@code enabled} as null when:
     * <ul>
     *   <li>The field is omitted from the JSON payload</li>
     *   <li>The no-args constructor is used (doesn't apply @Builder.Default)</li>
     * </ul>
     * This service-layer check provides defense-in-depth alongside the model's
     * {@code @JsonSetter(nulls = Nulls.SKIP)} annotation.
     *
     * @see UserProfileEntity#enabled
     */
    @Transactional
    public UserProfileResponse createProfile(UserProfileRequest request, String userId) {
        if (userProfileRepository.findByUserId(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Profile already exists");
        }
        UserProfileEntity profile = UserProfileEntity.builder()
                .userId(userId)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .mobileNumber(request.mobileNumber())
                .gender(request.gender())
                .age(request.age())
                .enabled(true)
                .build();

        UserProfileEntity saved = userProfileRepository.save(profile);
        return UserProfileResponse.fromEntity(saved);
    }

    @Transactional
    public void deleteProfile(String userId) {
        UserProfileEntity profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
        userProfileRepository.delete(profile);
    }

    /**
     * Admin: Creates a user in Keycloak and then creates a corresponding profile in the database.
     * Implements Saga pattern with compensation: if profile creation fails, the Keycloak user is deleted.
     */
    @Transactional
    public UserProfileResponse createAdminUser(UserRegistrationDTO dto, String token) {
        // 1. Create in Keycloak
        String userId = keycloakAdminClient.createUser(dto, token);

        // 2. Create Profile with compensation on failure
        try {
            UserProfileEntity profile = UserProfileEntity.builder()
                    .userId(userId)
                    .firstName(dto.firstName())
                    .lastName(dto.lastName())
                    .email(dto.email())
                    .gender(dto.gender())
                    .age(dto.age())
                    .build();

            UserProfileEntity saved = userProfileRepository.save(profile);
            return UserProfileResponse.fromEntity(saved);
        } catch (Exception e) {
            // Compensate: delete the Keycloak user to maintain consistency
            log.error("Failed to create profile for user {}. Compensating by deleting Keycloak user.", userId, e);
            try {
                keycloakAdminClient.deleteUser(userId, token);
            } catch (Exception compensationError) {
                log.error("Compensation failed: could not delete Keycloak user {}. Manual cleanup required.", userId, compensationError);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create user profile");
        }
    }

    /**
     * Admin: Deletes a user from both database and Keycloak.
     * <p>
     * <b>Saga Order Rationale:</b>
     * Profile is deleted first because:
     * <ul>
     *   <li>If profile deletion fails, Keycloak user remains intact (no partial state)</li>
     *   <li>If Keycloak deletion fails after profile is deleted, we have an orphan in IdP
     *       which is safer than an orphan in app DB (user can't authenticate without profile)</li>
     *   <li>Keycloak orphans can be cleaned up via admin console; app DB orphans may cause
     *       foreign key issues or data inconsistency</li>
     * </ul>
     */
    @Transactional
    public void deleteAdminUser(String userId, String token) {
        // 1. Delete Profile first (local transaction - can be rolled back)
        userProfileRepository.deleteByUserId(userId);

        // 2. Delete Keycloak User (external system - no rollback possible)
        // If this fails, we have an orphan in Keycloak but user cannot access the app
        try {
            keycloakAdminClient.deleteUser(userId, token);
        } catch (Exception e) {
            log.error("Failed to delete Keycloak user {} after profile deletion. Manual cleanup required.", userId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Profile deleted but Keycloak user deletion failed. Contact admin for cleanup.");
        }
    }
}

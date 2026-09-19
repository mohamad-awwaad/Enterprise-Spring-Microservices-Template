package com.example.adminservice.controller;

import com.example.adminservice.dto.RegisterUserDTO;
import com.example.adminservice.dto.UserDTO;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

/**
 * Controller for Keycloak user administration.
 * <p>
 * External path: /admin/ (via Gateway)
 * Internal path: /api/ (after Gateway transforms /admin/ -> /api/)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final Keycloak keycloak;

    @Value("${keycloak.admin.realm}")
    private String realm;

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createUser(@RequestBody UserDTO userDto) {
        UserRepresentation user = getUserRepresentation(userDto);

        RealmResource realmResource = keycloak.realm(realm);
        UsersResource usersResource = realmResource.users();

        try (Response response = usersResource.create(user)) {
            if (response.getStatus() == 201) {
                String userId = CreatedResponseUtil.getCreatedId(response);
                return ResponseEntity.status(HttpStatus.CREATED).body(userId);
            } else {
                return ResponseEntity.status(response.getStatus()).body("Failed to create user");
            }
        }
    }

    private static @NonNull UserRepresentation getUserRepresentation(UserDTO userDto) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(userDto.getUsername());
        user.setEmail(userDto.getEmail());
        user.setFirstName(userDto.getFirstName());
        user.setLastName(userDto.getLastName());
        user.setEnabled(true);

        if (userDto.getPassword() != null) {
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(userDto.getPassword());
            cred.setTemporary(false);
            user.setCredentials(Collections.singletonList(cred));
        }
        return user;
    }

    /**
     * Creates a user for self-registration flow (internal, service-to-service use only).
     * <p>
     * <b>Security Notes:</b>
     * <ul>
     *   <li>Protected by {@code @PreAuthorize("hasRole('INTERNAL_SERVICE')")}: this endpoint is no
     *       longer in {@code security.resource-server.public-endpoints}, so it now requires a
     *       valid bearer token carrying the {@code INTERNAL_SERVICE} realm role, which only the
     *       {@code internal-client} service account holds (client-credentials grant). The
     *       Gateway's block route on {@code /admin/users/register} remains as defense in depth
     *       for external callers, but this endpoint is also directly authenticated so it is safe
     *       even if reached without going through the Gateway.</li>
     *   <li>User is created with emailVerified=true since we already confirmed via our flow</li>
     *   <li>User is created WITHOUT a password; Keycloak sends UPDATE_PASSWORD action email</li>
     *   <li>This prevents password from ever being transmitted or stored by our services
     *       (only the confirmation token's BCrypt hash is temporarily stored)</li>
     * </ul>
     * <p>
     * <b>Called by:</b> Profile-service's RegistrationService.confirm() after successful email
     * verification, using a client-credentials token obtained for the {@code internal} client
     * registration (see profile-service's KeycloakAdminClient).
     */
    @PostMapping("/users/register")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<?> registerUser(@RequestBody RegisterUserDTO dto) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setEnabled(true);
        user.setEmailVerified(true); // Already verified via our confirmation flow

        RealmResource realmResource = keycloak.realm(realm);
        UsersResource usersResource = realmResource.users();

        try (Response response = usersResource.create(user)) {
            if (response.getStatus() == 201) {
                String userId = CreatedResponseUtil.getCreatedId(response);

                // Trigger password setup email
                if (dto.isSendPasswordEmail()) {
                    try {
                        usersResource.get(userId).executeActionsEmail(List.of("UPDATE_PASSWORD"));
                        log.info("Password setup email sent for user: {}", userId);
                    } catch (Exception e) {
                        log.error("Failed to send password email for user {}: {}", userId, e.getMessage());
                        // User is created, but email failed - don't fail the whole operation
                    }
                }

                return ResponseEntity.status(HttpStatus.CREATED).body(userId);
            } else {
                return ResponseEntity.status(response.getStatus()).body("Failed to create user");
            }
        }
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        RealmResource realmResource = keycloak.realm(realm);
        UsersResource usersResource = realmResource.users();
        try {
            usersResource.get(id).remove();
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete user {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete user");
        }
    }
}

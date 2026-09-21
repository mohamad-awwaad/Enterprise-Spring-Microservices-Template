package com.example.profileservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * Temporary storage for registration data until email/mobile confirmation.
 * <p>
 * <b>Lifecycle:</b>
 * <ul>
 *   <li>Created when user submits registration form</li>
 *   <li>Deleted when user confirms via link/OTP</li>
 *   <li>Deleted by cleanup job if expired</li>
 * </ul>
 * <p>
 * <b>Security:</b>
 * <ul>
 *   <li>Carries no password: the self-registration flow never collects one - the user's real
 *       password is set directly with Keycloak via the "Set Password" email sent after
 *       confirmation (see {@code RegistrationService}). A {@code password_hash} column may
 *       still linger in an existing dev database from before this change - {@code ddl-auto=update}
 *       adds/alters columns but never drops them, so it is simply unused going forward, not
 *       actively removed by this template.</li>
 *   <li>Maps 1:1 with UserProfileEntity (shares same ID) for easy cleanup</li>
 * </ul>
 */
@Entity
@Table(name = "pending_registrations")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingRegistrationEntity {

    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "id")
    @MapsId
    @ToString.Exclude
    private UserProfileEntity userProfile;

    @Column(nullable = false, unique = true)
    private String confirmationToken;

    @Column(nullable = false)
    private Instant tokenExpiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConfirmationType confirmationType;

    @Column(nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingRegistrationEntity that = (PendingRegistrationEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
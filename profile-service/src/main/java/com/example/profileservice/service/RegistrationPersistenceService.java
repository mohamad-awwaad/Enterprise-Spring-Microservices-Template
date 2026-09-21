package com.example.profileservice.service;

import com.example.profileservice.dto.SelfRegistrationRequest;
import com.example.profileservice.model.ConfirmationType;
import com.example.profileservice.model.PendingRegistrationEntity;
import com.example.profileservice.model.UserProfileEntity;
import com.example.profileservice.repository.PendingRegistrationEntityRepository;
import com.example.profileservice.repository.UserProfileEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists a new {@link UserProfileEntity} + {@link PendingRegistrationEntity} pair for
 * self-registration, in its own transaction.
 * <p>
 * Split out of {@link RegistrationService} specifically so the unique-email race described in
 * {@link RegistrationService#register} can be caught cleanly. Once a
 * {@code DataIntegrityViolationException} (or any other exception) escapes a
 * {@code @Transactional} method, Spring marks *that* transaction rollback-only; if this insert
 * lived directly inside {@code RegistrationService#register} and that method were itself
 * {@code @Transactional}, a try/catch around the failing call would not help - the surrounding
 * transaction would still be unusable, and the AOP self-invocation problem means calling another
 * {@code @Transactional} method on {@code this} wouldn't even apply the new propagation in the
 * first place. Routing the insert through this separate bean's own
 * {@code @Transactional(REQUIRES_NEW)} method gives it an independent transaction: on failure,
 * only that nested transaction rolls back, and the caller (deliberately not itself
 * {@code @Transactional}) is free to catch the exception and return normally.
 */
@Component
@RequiredArgsConstructor
public class RegistrationPersistenceService {

    private final UserProfileEntityRepository userProfileRepository;
    private final PendingRegistrationEntityRepository pendingRegistrationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(SelfRegistrationRequest request, String confirmationToken, Instant tokenExpiry) {
        UserProfileEntity profile = UserProfileEntity.builder()
                .userId(UUID.randomUUID().toString()) // Temporary, replaced after KC user creation
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .mobileNumber(request.mobileNumber())
                .gender(request.gender())
                .age(request.age())
                .enabled(false)
                .build();

        // saveAndFlush (rather than save, which Hibernate may defer to commit) so the unique
        // constraint on user_profiles.email is checked - and any violation thrown - inside this
        // REQUIRES_NEW transaction, where the caller's try/catch is set up to handle it.
        profile = userProfileRepository.saveAndFlush(profile);

        PendingRegistrationEntity pending = PendingRegistrationEntity.builder()
                .userProfile(profile)
                .confirmationToken(confirmationToken)
                .tokenExpiry(tokenExpiry)
                .confirmationType(ConfirmationType.EMAIL)
                .createdAt(Instant.now())
                .build();

        pendingRegistrationRepository.save(pending);
    }
}

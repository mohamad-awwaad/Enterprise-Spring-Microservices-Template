package com.example.profileservice.dto;

import com.example.profileservice.model.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for public self-registration ({@code POST /api/public/register}).
 * <p>
 * Deliberately separate from {@link UserRegistrationDTO} (still used by the admin
 * user-creation path): the public flow never had a real use for a {@code username} (email is
 * the username end to end) or a {@code password} (it was BCrypt-hashed into
 * {@code PendingRegistrationEntity} but never actually read back anywhere - the user always sets
 * their real password later via Keycloak's own "Set Password" email). Splitting the DTOs lets
 * each endpoint validate only the fields it actually consumes.
 */
public record SelfRegistrationRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Invalid mobile number format (E.164)")
        String mobileNumber,

        Gender gender,

        @Min(value = 0, message = "Age must be a positive number")
        @Max(value = 150, message = "Age must be realistic")
        Integer age
) {
}

package com.example.adminservice.dto;

/**
 * DTO for self-registration flow.
 * Creates user without password and optionally triggers password setup email.
 * <p>
 * {@code sendPasswordEmail} is a {@link Boolean} wrapper (not a primitive {@code boolean})
 * because Jackson 3's record deserialization rejects a JSON payload that omits a primitive
 * component with {@code MismatchedInputException: Cannot map null into type boolean} - unlike
 * the previous Lombok {@code @Data} class, which silently left an unset field at Java's default
 * (false). The compact constructor below restores that "missing means false" default.
 */
public record RegisterUserDTO(
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean sendPasswordEmail
) {
    public RegisterUserDTO {
        if (sendPasswordEmail == null) {
            sendPasswordEmail = false;
        }
    }
}

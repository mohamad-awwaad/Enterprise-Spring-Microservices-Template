package com.example.profileservice.dto;

import com.example.profileservice.model.Gender;
import jakarta.validation.constraints.*;
import lombok.Builder;

/**
 * DTO for profile creation/update requests.
 */
@Builder
public record UserProfileRequest(

        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        @Email(message = "Invalid email format")
        String email,

        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Invalid mobile number format (E.164)")
        String mobileNumber,

        Gender gender,

        @Min(value = 0, message = "Age must be a positive number")
        @Max(value = 150, message = "Age must be realistic")
        Integer age
) {
}

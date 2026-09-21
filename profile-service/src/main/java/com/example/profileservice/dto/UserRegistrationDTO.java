package com.example.profileservice.dto;

import com.example.profileservice.model.Gender;
import jakarta.validation.constraints.*;

public record UserRegistrationDTO(

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Invalid mobile number format (E.164)")
        String mobileNumber,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        Gender gender,

        @Min(value = 0, message = "Age must be a positive number")
        @Max(value = 150, message = "Age must be realistic")
        Integer age
) {
}

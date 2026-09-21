package com.example.adminservice.dto;

public record UserDTO(
        String username,
        String email,
        String firstName,
        String lastName,
        String password
) {
}

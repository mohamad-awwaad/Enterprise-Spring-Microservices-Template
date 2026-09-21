package com.example.profileservice.dto;

import com.example.profileservice.model.UserProfileEntity;
import com.example.profileservice.model.Gender;
import lombok.Builder;

@Builder
public record UserProfileResponse(
        String userId,
        String firstName,
        String lastName,
        String email,
        String mobileNumber,
        Gender gender,
        Integer age,
        Boolean enabled
) {

    public static UserProfileResponse fromEntity(UserProfileEntity entity) {
        return UserProfileResponse.builder()
                .userId(entity.getUserId())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .email(entity.getEmail())
                .mobileNumber(entity.getMobileNumber())
                .gender(entity.getGender())
                .age(entity.getAge())
                .enabled(entity.getEnabled())
                .build();
    }
}

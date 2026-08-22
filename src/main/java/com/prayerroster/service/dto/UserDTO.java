package com.prayerroster.service.dto;

import com.prayerroster.domain.User;

public record UserDTO(
    String id,
    String email,
    String firstName,
    String lastName,
    boolean active,
    boolean canModerate,
    boolean canPreach,
    String roleName
) {
    public static UserDTO from(User user) {
        return new UserDTO(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.isActive(),
            user.isCanModerate(),
            user.isCanPreach(),
            user.getRole().getName()
        );
    }
}

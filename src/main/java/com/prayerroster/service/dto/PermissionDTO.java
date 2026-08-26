package com.prayerroster.service.dto;

import com.prayerroster.domain.Permission;

public record PermissionDTO(Long id, String code, String description) {
    public static PermissionDTO from(Permission permission) {
        return new PermissionDTO(permission.getId(), permission.getCode(), permission.getDescription());
    }
}

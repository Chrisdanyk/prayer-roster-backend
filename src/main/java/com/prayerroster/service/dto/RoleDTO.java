package com.prayerroster.service.dto;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import java.util.List;

public record RoleDTO(Long id, String name, String description, List<String> permissionCodes, long userCount) {
    public static RoleDTO from(Role role, long userCount) {
        return new RoleDTO(
            role.getId(),
            role.getName(),
            role.getDescription(),
            role.getPermissions().stream().map(Permission::getCode).sorted().toList(),
            userCount
        );
    }
}

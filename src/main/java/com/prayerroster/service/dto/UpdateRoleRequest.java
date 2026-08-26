package com.prayerroster.service.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code name} is optional: absent or unchanged leaves the role's name alone. When present and
 * different, {@code RoleService} rejects it for the three baseline roles and for a name already
 * held by another role - custom roles are otherwise renameable.
 */
public record UpdateRoleRequest(
    @Pattern(regexp = "\\S.*", message = "Name cannot be blank") @Size(max = 50) String name,
    @Size(max = 200) String description,
    List<String> permissionCodes
) {}

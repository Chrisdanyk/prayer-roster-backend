package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateRoleRequest(
    @NotBlank @Size(max = 50) String name,
    @Size(max = 200) String description,
    List<String> permissionCodes
) {}

package com.prayerroster.service.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateRoleRequest(@Size(max = 200) String description, List<String> permissionCodes) {}

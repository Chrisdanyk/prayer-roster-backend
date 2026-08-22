package com.prayerroster.service.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(@Size(max = 100) String firstName, @Size(max = 100) String lastName, boolean canModerate, boolean canPreach) {}

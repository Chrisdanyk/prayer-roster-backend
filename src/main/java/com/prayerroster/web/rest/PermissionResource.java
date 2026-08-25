package com.prayerroster.web.rest;

import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.service.dto.PermissionDTO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only. The catalogue is defined in {@code config/permissions.json} and re-seeded at every
 * boot, so write endpoints would fight the seeder - see docs/phase1-architecture.md section 9.
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionResource {

    private final PermissionRepository permissionRepository;

    public PermissionResource(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PERMISSION_VIEW')")
    public List<PermissionDTO> getPermissions() {
        return permissionRepository.findAll().stream().map(PermissionDTO::from).sorted((a, b) -> a.code().compareTo(b.code())).toList();
    }
}

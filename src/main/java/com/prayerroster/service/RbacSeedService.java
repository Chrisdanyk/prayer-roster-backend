package com.prayerroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.security.RoleNames;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the permission catalog from {@code config/permissions.json} and the three baseline roles
 * (SUPER_ADMIN, ADMIN, USER) on startup. Idempotent: permissions are upserted by code every run
 * (descriptions may change), but a role's permission assignment is only set at role creation time -
 * once a role exists, the database is the source of truth and an admin's later customization via the
 * API is never overwritten by a restart. See docs/phase1-architecture.md section 9.
 */
@Service
public class RbacSeedService implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RbacSeedService.class);

    private static final List<String> ADMIN_DEFAULT_PERMISSIONS = List.of(
        "USER_VIEW",
        // Inviting is routine operational work and escalates nothing - an invited account is
        // provisioned with role USER and no permissions. USER_DELETE is deliberately withheld:
        // removal is destructive, and the same generic code would gate a future
        // DELETE /api/users/{id}. See docs/phase1-architecture.md section 10.
        "USER_CREATE",
        "USER_UPDATE",
        "PRAYER_CONFIG_VIEW",
        "PRAYER_CONFIG_UPDATE",
        "ROSTER_VIEW",
        "ROSTER_GENERATE",
        "ROSTER_REGENERATE",
        "ROSTER_RESCHEDULE",
        "AVAILABILITY_VIEW",
        "AVAILABILITY_MANAGE",
        "REMINDER_CONFIG_VIEW",
        "REMINDER_CONFIG_UPDATE"
    );

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final ObjectMapper objectMapper;

    public RbacSeedService(PermissionRepository permissionRepository, RoleRepository roleRepository, ObjectMapper objectMapper) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        seedPermissionCatalog();
        seedRole(
            RoleNames.SUPER_ADMIN,
            "Accès complet à l'application",
            permissionRepository.findAll().stream().map(Permission::getCode).toList()
        );
        seedRole(RoleNames.ADMIN, "Gestion opérationnelle des plannings de prière", ADMIN_DEFAULT_PERMISSIONS);
        // PRAYER_CONFIG_VIEW only: a member sees which days have prayer and which need a preacher,
        // never who is serving - /api/prayer-sessions stays behind ROSTER_VIEW. Without it a
        // member's calendar is one highlighted day in an empty month. See section 10.
        seedRole(RoleNames.USER, "Utilisateur standard - accès à ses propres données uniquement", List.of("PRAYER_CONFIG_VIEW"));
    }

    @Transactional
    void seedPermissionCatalog() throws IOException {
        List<PermissionEntry> catalog = readCatalog();
        for (PermissionEntry entry : catalog) {
            Permission permission = permissionRepository.findByCode(entry.code()).orElseGet(Permission::new);
            permission.setCode(entry.code());
            permission.setDescription(entry.description());
            permissionRepository.save(permission);
        }
        LOG.info("Permission catalog seeded: {} entries", catalog.size());
    }

    @Transactional
    void seedRole(String name, String description, List<String> defaultPermissionCodes) {
        if (roleRepository.findByName(name).isPresent()) {
            return;
        }
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setPermissions(resolvePermissions(defaultPermissionCodes));
        roleRepository.save(role);
        LOG.info("Role seeded: {} with {} permission(s)", name, role.getPermissions().size());
    }

    private Set<Permission> resolvePermissions(List<String> codes) {
        Set<Permission> permissions = new HashSet<>();
        for (String code : codes) {
            permissionRepository.findByCode(code).ifPresent(permissions::add);
        }
        return permissions;
    }

    private List<PermissionEntry> readCatalog() throws IOException {
        try (InputStream stream = new ClassPathResource("config/permissions.json").getInputStream()) {
            return Arrays.asList(objectMapper.readValue(stream, PermissionEntry[].class));
        }
    }

    record PermissionEntry(String code, String description) {}
}

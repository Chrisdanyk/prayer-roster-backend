package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.security.RoleNames;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class RbacSeedServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RoleRepository roleRepository;

    private RbacSeedService service;

    @BeforeEach
    void setUp() {
        service = new RbacSeedService(permissionRepository, roleRepository, new ObjectMapper());
    }

    @Test
    void seedPermissionCatalog_createsEveryEntryInPermissionsJson() throws IOException {
        when(permissionRepository.findByCode(any())).thenReturn(Optional.empty());

        service.seedPermissionCatalog();

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionRepository, times(22)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Permission::getCode).contains(
            "USER_VIEW",
            "ROLE_DELETE",
            "PERMISSION_CREATE",
            "PRAYER_CONFIG_UPDATE",
            "ROSTER_GENERATE",
            "ROSTER_PUBLISH",
            "ROSTER_RESCHEDULE",
            "AVAILABILITY_MANAGE",
            "NOTIFICATION_VIEW"
        );
    }

    @Test
    void seedPermissionCatalog_updatesDescriptionOfExistingPermission() throws IOException {
        Permission existing = new Permission();
        existing.setId(1L);
        existing.setCode("USER_VIEW");
        existing.setDescription("stale description");
        when(permissionRepository.findByCode("USER_VIEW")).thenReturn(Optional.of(existing));
        when(permissionRepository.findByCode(argThat(code -> !"USER_VIEW".equals(code)))).thenReturn(Optional.empty());

        service.seedPermissionCatalog();

        assertThat(existing.getDescription()).isEqualTo("Consulter les utilisateurs");
        verify(permissionRepository).save(existing);
    }

    @Test
    void seedRole_doesNothingWhenRoleAlreadyExists() {
        when(roleRepository.findByName(RoleNames.USER)).thenReturn(Optional.of(new Role()));

        service.seedRole(RoleNames.USER, "description", List.of());

        verify(roleRepository, never()).save(any());
    }

    @Test
    void seedRole_createsRoleWithResolvedPermissionsWhenAbsent() {
        Permission viewPermission = new Permission();
        viewPermission.setId(1L);
        viewPermission.setCode("USER_VIEW");
        when(roleRepository.findByName(RoleNames.ADMIN)).thenReturn(Optional.empty());
        when(permissionRepository.findByCode("USER_VIEW")).thenReturn(Optional.of(viewPermission));
        when(permissionRepository.findByCode("MISSING_CODE")).thenReturn(Optional.empty());

        service.seedRole(RoleNames.ADMIN, "Admin role", List.of("USER_VIEW", "MISSING_CODE"));

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(captor.capture());
        Role saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo(RoleNames.ADMIN);
        assertThat(saved.getPermissions()).containsExactly(viewPermission);
    }

    @Test
    void run_seedsSuperAdminWithEveryPermissionInTheCatalog() throws IOException {
        Permission all1 = new Permission();
        all1.setId(1L);
        all1.setCode("USER_VIEW");
        Permission all2 = new Permission();
        all2.setId(2L);
        all2.setCode("ROSTER_GENERATE");
        when(permissionRepository.findByCode(any())).thenReturn(Optional.empty());
        when(permissionRepository.findAll()).thenReturn(List.of(all1, all2));
        when(roleRepository.findByName(any())).thenReturn(Optional.empty());
        when(permissionRepository.findByCode("USER_VIEW")).thenReturn(Optional.of(all1));
        when(permissionRepository.findByCode("ROSTER_GENERATE")).thenReturn(Optional.of(all2));

        service.run(new DefaultApplicationArguments());

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, times(3)).save(captor.capture());
        Role superAdmin = captor.getAllValues().stream().filter(r -> RoleNames.SUPER_ADMIN.equals(r.getName())).findFirst().orElseThrow();
        assertThat(superAdmin.getPermissions()).containsExactlyInAnyOrder(all1, all2);
        Role user = captor.getAllValues().stream().filter(r -> RoleNames.USER.equals(r.getName())).findFirst().orElseThrow();
        assertThat(user.getPermissions()).isEmpty();
    }
}

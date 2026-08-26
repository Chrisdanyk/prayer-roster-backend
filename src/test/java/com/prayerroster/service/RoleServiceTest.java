package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.DynamicAuthoritiesService;
import com.prayerroster.security.RoleNames;
import com.prayerroster.service.dto.CreateRoleRequest;
import com.prayerroster.service.dto.RoleDTO;
import com.prayerroster.service.dto.UpdateRoleRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DynamicAuthoritiesService authoritiesService;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleRepository, permissionRepository, userRepository, authoritiesService);
        // RoleService relies on an active transaction to register its afterCommit eviction; in
        // production this is provided by the @Transactional proxy, so a plain unit test must open one
        // itself to exercise that registration.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** Simulates the container invoking the registered synchronization once the transaction commits. */
    private void triggerAfterCommit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void findAll_returnsRolesWithTheirPermissionCodesAndUserCount() {
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(role(1L, RoleNames.ADMIN, "USER_VIEW")));
        when(userRepository.countByRoleId(1L)).thenReturn(3L);

        List<RoleDTO> result = service.findAll();

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.name()).isEqualTo(RoleNames.ADMIN);
            assertThat(dto.permissionCodes()).containsExactly("USER_VIEW");
            assertThat(dto.userCount()).isEqualTo(3L);
        });
    }

    @Test
    void create_rejectsADuplicateName() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.of(role(9L, "COORDINATOR")));

        assertThatThrownBy(() -> service.create(new CreateRoleRequest("COORDINATOR", null, List.of())))
            .isInstanceOf(BadRequestAlertException.class);

        verify(roleRepository, never()).save(any());
    }

    @Test
    void create_rejectsAnUnknownPermissionCode() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.empty());
        when(permissionRepository.findByCode("NOT_A_CODE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateRoleRequest("COORDINATOR", null, List.of("NOT_A_CODE"))))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("NOT_A_CODE");
    }

    @Test
    void create_registersAnAfterCommitEvictionRatherThanCallingItInline() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateRoleRequest("COORDINATOR", "Coordination", List.of()));

        verify(authoritiesService, never()).evictAll();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        triggerAfterCommit();
        verify(authoritiesService).evictAll();
    }

    @Test
    void update_replacesThePermissionSetAndRegistersAnAfterCommitEviction() {
        Role existing = role(2L, "COORDINATOR", "USER_VIEW");
        Permission granted = permission("ROSTER_VIEW");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(permissionRepository.findByCode("ROSTER_VIEW")).thenReturn(Optional.of(granted));
        when(roleRepository.save(existing)).thenReturn(existing);
        when(userRepository.countByRoleId(2L)).thenReturn(0L);

        RoleDTO result = service.update(2L, new UpdateRoleRequest(null, "Coordination", List.of("ROSTER_VIEW")));

        assertThat(result.permissionCodes()).containsExactly("ROSTER_VIEW");
        verify(authoritiesService, never()).evictAll();
        triggerAfterCommit();
        verify(authoritiesService).evictAll();
    }

    @Test
    void update_allowsAddingPermissionsToSuperAdmin() {
        Role superAdmin = role(1L, RoleNames.SUPER_ADMIN, "USER_VIEW");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(permissionRepository.findByCode("USER_VIEW")).thenReturn(Optional.of(permission("USER_VIEW")));
        when(permissionRepository.findByCode("ROSTER_VIEW")).thenReturn(Optional.of(permission("ROSTER_VIEW")));
        when(roleRepository.save(superAdmin)).thenReturn(superAdmin);
        when(userRepository.countByRoleId(1L)).thenReturn(1L);

        RoleDTO result = service.update(1L, new UpdateRoleRequest(null, null, List.of("USER_VIEW", "ROSTER_VIEW")));

        assertThat(result.permissionCodes()).containsExactly("ROSTER_VIEW", "USER_VIEW");
    }

    @Test
    void update_refusesToStripPermissionsFromSuperAdmin() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, RoleNames.SUPER_ADMIN, "USER_VIEW")));

        // Swapping one permission for another must fail too, not just shrinking the set.
        assertThatThrownBy(() -> service.update(1L, new UpdateRoleRequest(null, null, List.of())))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("SUPER_ADMIN");

        verify(roleRepository, never()).save(any());
    }

    @Test
    void update_refusesToSwapAPermissionOnSuperAdmin() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, RoleNames.SUPER_ADMIN, "ROLE_UPDATE")));
        when(permissionRepository.findByCode("ROSTER_VIEW")).thenReturn(Optional.of(permission("ROSTER_VIEW")));

        // Same permission count, but ROLE_UPDATE is gone - a size comparison alone would miss this.
        assertThatThrownBy(() -> service.update(1L, new UpdateRoleRequest(null, null, List.of("ROSTER_VIEW"))))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("SUPER_ADMIN");

        verify(roleRepository, never()).save(any());
    }

    @Test
    void update_rejectsAnUnknownRole() {
        when(roleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new UpdateRoleRequest(null, null, List.of())))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void update_leavesTheNameAloneWhenAbsent() {
        Role existing = role(2L, "COORDINATOR");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(roleRepository.save(existing)).thenReturn(existing);
        when(userRepository.countByRoleId(2L)).thenReturn(0L);

        RoleDTO result = service.update(2L, new UpdateRoleRequest(null, "New description", List.of()));

        assertThat(result.name()).isEqualTo("COORDINATOR");
        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void update_leavesTheNameAloneWhenUnchanged() {
        Role existing = role(2L, "COORDINATOR");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(roleRepository.save(existing)).thenReturn(existing);
        when(userRepository.countByRoleId(2L)).thenReturn(0L);

        RoleDTO result = service.update(2L, new UpdateRoleRequest("COORDINATOR", null, List.of()));

        assertThat(result.name()).isEqualTo("COORDINATOR");
        verify(roleRepository, never()).findByName("COORDINATOR");
    }

    @Test
    void update_renamesACustomRoleToAnUnusedName() {
        Role existing = role(2L, "COORDINATOR");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(roleRepository.findByName("SUPERVISOR")).thenReturn(Optional.empty());
        when(roleRepository.save(existing)).thenReturn(existing);
        when(userRepository.countByRoleId(2L)).thenReturn(0L);

        RoleDTO result = service.update(2L, new UpdateRoleRequest("SUPERVISOR", null, List.of()));

        assertThat(result.name()).isEqualTo("SUPERVISOR");
    }

    @Test
    void update_refusesToRenameABaselineRole() {
        Role admin = role(1L, RoleNames.ADMIN);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.update(1L, new UpdateRoleRequest("SUPERVISOR", null, List.of())))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("Baseline");

        verify(roleRepository, never()).save(any());
    }

    @Test
    void update_refusesToRenameToANameAnotherRoleAlreadyHolds() {
        Role existing = role(2L, "COORDINATOR");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(roleRepository.findByName("SUPERVISOR")).thenReturn(Optional.of(role(3L, "SUPERVISOR")));

        assertThatThrownBy(() -> service.update(2L, new UpdateRoleRequest("SUPERVISOR", null, List.of())))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("SUPERVISOR");

        verify(roleRepository, never()).save(any());
    }

    @Test
    void delete_rejectsAnUnknownRole() {
        when(roleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(EntityNotFoundException.class);

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void create_treatsANullPermissionCodesListAsNoPermissions() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleDTO result = service.create(new CreateRoleRequest("COORDINATOR", null, null));

        assertThat(result.permissionCodes()).isEmpty();
    }

    @Test
    void delete_refusesABaselineRole() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, RoleNames.USER)));

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BadRequestAlertException.class);

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_refusesARoleThatUsersStillHold() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(role(5L, "COORDINATOR")));
        when(userRepository.countByRoleId(5L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(5L))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("2");

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_removesAnUnusedCustomRoleAndRegistersAnAfterCommitEviction() {
        Role custom = role(5L, "COORDINATOR");
        when(roleRepository.findById(5L)).thenReturn(Optional.of(custom));
        when(userRepository.countByRoleId(5L)).thenReturn(0L);

        service.delete(5L);

        verify(roleRepository).delete(custom);
        verify(authoritiesService, never()).evictAll();
        triggerAfterCommit();
        verify(authoritiesService).evictAll();
    }

    private Role role(Long id, String name, String... permissionCodes) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        role.setPermissions(new java.util.HashSet<>(java.util.Arrays.stream(permissionCodes).map(this::permission).toList()));
        return role;
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        return permission;
    }
}

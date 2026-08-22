package com.prayerroster.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.security.oauth2.GoogleIdentity;
import com.prayerroster.service.UserProvisioningService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;

@ExtendWith(MockitoExtension.class)
class DynamicAuthoritiesServiceTest {

    private static final GoogleIdentity IDENTITY = new GoogleIdentity("sub-1", "jean@example.com", "Jean", "Dupont");

    @Mock
    private UserProvisioningService provisioningService;

    private DynamicAuthoritiesService service;

    @BeforeEach
    void setUp() {
        service = new DynamicAuthoritiesService(provisioningService);
    }

    @Test
    void resolveAuthorities_mapsRolePermissionsToPrefixedAuthorities() {
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.ADMIN, "ROSTER_GENERATE", "USER_VIEW"));

        Set<GrantedAuthority> authorities = service.resolveAuthorities(IDENTITY);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            "PERM_ROSTER_GENERATE",
            "PERM_USER_VIEW"
        );
    }

    @Test
    void resolveAuthorities_alsoGrantsStaticAdminAuthorityForSuperAdmin() {
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.SUPER_ADMIN, "ROSTER_GENERATE"));

        Set<GrantedAuthority> authorities = service.resolveAuthorities(IDENTITY);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            "PERM_ROSTER_GENERATE",
            AuthoritiesConstants.ADMIN
        );
    }

    @Test
    void resolveAuthorities_doesNotGrantStaticAdminAuthorityForPlainAdmin() {
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.ADMIN, "ROSTER_GENERATE"));

        Set<GrantedAuthority> authorities = service.resolveAuthorities(IDENTITY);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).doesNotContain(AuthoritiesConstants.ADMIN);
    }

    @Test
    void resolveAuthorities_returnsNoAuthoritiesForInactiveUser() {
        User inactive = userWith(RoleNames.SUPER_ADMIN, "ROSTER_GENERATE");
        inactive.setActive(false);
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(inactive);

        Set<GrantedAuthority> authorities = service.resolveAuthorities(IDENTITY);

        assertThat(authorities).isEmpty();
    }

    @Test
    void resolveAuthorities_cachesResultAndDoesNotHitProvisioningTwice() {
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.USER));

        service.resolveAuthorities(IDENTITY);
        service.resolveAuthorities(IDENTITY);

        verify(provisioningService, times(1)).provisionOrRefresh(IDENTITY);
    }

    @Test
    void evict_forcesProvisioningToBeCalledAgain() {
        when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.USER));

        service.resolveAuthorities(IDENTITY);
        service.evict(IDENTITY.sub());
        service.resolveAuthorities(IDENTITY);

        verify(provisioningService, times(2)).provisionOrRefresh(IDENTITY);
    }

    private User userWith(String roleName, String... permissionCodes) {
        Role role = new Role();
        role.setId(1L);
        role.setName(roleName);
        Set<Permission> permissions = new java.util.HashSet<>();
        for (String code : permissionCodes) {
            Permission permission = new Permission();
            permission.setId((long) permissions.size() + 1);
            permission.setCode(code);
            permissions.add(permission);
        }
        role.setPermissions(permissions);
        User user = new User();
        user.setId(IDENTITY.sub());
        user.setActive(true);
        user.setRole(role);
        return user;
    }
}

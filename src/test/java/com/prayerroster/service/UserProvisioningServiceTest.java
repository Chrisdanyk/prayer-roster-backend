package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.RoleNames;
import com.prayerroster.security.oauth2.GoogleIdentity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class UserProvisioningServiceTest {

    private static final GoogleIdentity IDENTITY = new GoogleIdentity("sub-1", "jean@example.com", "Jean", "Dupont");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private ApplicationProperties applicationProperties;
    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        applicationProperties = new ApplicationProperties();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new UserProvisioningService(userRepository, roleRepository, applicationProperties, transactionManager);
    }

    @Test
    void provisionOrRefresh_returnsExistingUserUnchangedWhenProfileMatches() {
        User existing = existingUser();
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
    }

    @Test
    void provisionOrRefresh_updatesProfileWhenGoogleDataChanged() {
        User existing = existingUser();
        existing.setFirstName("Ancien prénom");
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getFirstName()).isEqualTo("Jean");
        verify(userRepository).save(existing);
    }

    @Test
    void provisionOrRefresh_updatesEmailWhenGoogleEmailChanged() {
        User existing = existingUser();
        existing.setEmail("old-email@example.com");
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getEmail()).isEqualTo("jean@example.com");
    }

    @Test
    void provisionOrRefresh_updatesLastNameWhenGoogleLastNameChanged() {
        User existing = existingUser();
        existing.setLastName("Ancien nom");
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getLastName()).isEqualTo("Dupont");
    }

    @Test
    void provisionOrRefresh_createsNewUserWithUserRoleByDefault() {
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
        when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getId()).isEqualTo("sub-1");
        assertThat(result.getRole().getName()).isEqualTo(RoleNames.USER);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getLangKey()).isEqualTo("fr");
    }

    @Test
    void provisionOrRefresh_promotesConfiguredBootstrapEmailToSuperAdmin() {
        applicationProperties.getSecurity().setInitialSuperAdminEmail("JEAN@EXAMPLE.COM");
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
        when(roleRepository.findByNameWithPermissions(RoleNames.SUPER_ADMIN)).thenReturn(Optional.of(roleWithName(RoleNames.SUPER_ADMIN)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getRole().getName()).isEqualTo(RoleNames.SUPER_ADMIN);
    }

    @Test
    void provisionOrRefresh_doesNotPromoteWhenBootstrapEmailDoesNotMatch() {
        applicationProperties.getSecurity().setInitialSuperAdminEmail("someone-else@example.com");
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
        when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result.getRole().getName()).isEqualTo(RoleNames.USER);
    }

    @Test
    void provisionOrRefresh_fallsBackToReadingWinnerRowWhenConcurrentCreateLosesRace() {
        User winnerRow = existingUser();
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty(), Optional.of(winnerRow));
        when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        User result = service.provisionOrRefresh(IDENTITY);

        assertThat(result).isSameAs(winnerRow);
        verify(userRepository, times(2)).findByIdWithRoleAndPermissions("sub-1");
    }

    @Test
    void provisionOrRefresh_rethrowsWhenRaceLostButWinnerRowStillNotFound() {
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
        when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.provisionOrRefresh(IDENTITY)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void provisionOrRefresh_throwsWhenRoleNotYetSeeded() {
        when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
        when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provisionOrRefresh(IDENTITY)).isInstanceOf(IllegalStateException.class).hasMessageContaining(
            RoleNames.USER
        );
    }

    private User existingUser() {
        User user = new User();
        user.setId(IDENTITY.sub());
        user.setEmail(IDENTITY.email());
        user.setFirstName(IDENTITY.firstName());
        user.setLastName(IDENTITY.lastName());
        user.setRole(roleWithName(RoleNames.USER));
        return user;
    }

    private Role roleWithName(String name) {
        Role role = new Role();
        role.setId(1L);
        role.setName(name);
        return role;
    }
}

package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.DynamicAuthoritiesService;
import com.prayerroster.security.RoleNames;
import com.prayerroster.service.dto.UpdateUserRequest;
import com.prayerroster.service.dto.UserDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private DynamicAuthoritiesService authoritiesService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserManagementService service;

    @BeforeEach
    void setUp() {
        service = new UserManagementService(userRepository, roleRepository, authoritiesService, eventPublisher);
    }

    private User user() {
        Role role = new Role();
        role.setId(1L);
        role.setName(RoleNames.USER);
        User user = new User();
        user.setId("sub-1");
        user.setEmail("jean@example.com");
        user.setFirstName("Jean");
        user.setLastName("Dupont");
        user.setActive(true);
        user.setRole(role);
        return user;
    }

    @Test
    void findAll_mapsPageOfUsersToDtos() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAllWithRole(pageable)).thenReturn(new PageImpl<>(List.of(user())));

        var page = service.findAll(pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("jean@example.com");
    }

    @Test
    void findOne_returnsDtoWhenFound() {
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(user()));

        UserDTO dto = service.findOne("sub-1");

        assertThat(dto.id()).isEqualTo("sub-1");
        assertThat(dto.roleName()).isEqualTo(RoleNames.USER);
    }

    @Test
    void findOne_throwsWhenNotFound() {
        when(userRepository.findByIdWithRole("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOne("missing")).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void update_setsFieldsSavesAndEvictsCache() {
        User existing = user();
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        UserDTO result = service.update("sub-1", new UpdateUserRequest("Marie", "Curie", true, false));

        assertThat(result.firstName()).isEqualTo("Marie");
        assertThat(result.lastName()).isEqualTo("Curie");
        assertThat(result.canModerate()).isTrue();
        assertThat(result.canPreach()).isFalse();
        verify(authoritiesService).evict("sub-1");
    }

    @Test
    void update_throwsWhenNotFound() {
        when(userRepository.findByIdWithRole("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("missing", new UpdateUserRequest("A", "B", false, false))).isInstanceOf(
            EntityNotFoundException.class
        );
    }

    @Test
    void updateStatus_togglesActiveAndEvictsCacheAndPublishesDeactivationEvent() {
        User existing = user();
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        UserDTO result = service.updateStatus("sub-1", false);

        assertThat(result.active()).isFalse();
        verify(authoritiesService).evict("sub-1");
        verify(eventPublisher).publishEvent(new UserDeactivatedEvent("sub-1"));
    }

    @Test
    void updateStatus_doesNotPublishWhenStayingActive() {
        User existing = user();
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        service.updateStatus("sub-1", true);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateStatus_doesNotPublishWhenReactivating() {
        User existing = user();
        existing.setActive(false);
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        UserDTO result = service.updateStatus("sub-1", true);

        assertThat(result.active()).isTrue();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void assignRole_updatesRoleAndEvictsCache() {
        User existing = user();
        Role adminRole = new Role();
        adminRole.setId(2L);
        adminRole.setName(RoleNames.ADMIN);
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(existing));
        when(roleRepository.findByName(RoleNames.ADMIN)).thenReturn(Optional.of(adminRole));
        when(userRepository.save(existing)).thenReturn(existing);

        UserDTO result = service.assignRole("sub-1", RoleNames.ADMIN);

        assertThat(result.roleName()).isEqualTo(RoleNames.ADMIN);
        verify(authoritiesService).evict("sub-1");
    }

    @Test
    void assignRole_throwsBadRequestWhenRoleUnknown() {
        when(userRepository.findByIdWithRole("sub-1")).thenReturn(Optional.of(user()));
        when(roleRepository.findByName("NOT_A_ROLE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignRole("sub-1", "NOT_A_ROLE")).isInstanceOf(BadRequestAlertException.class);
    }
}

package com.prayerroster.service;

import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.DynamicAuthoritiesService;
import com.prayerroster.service.dto.UpdateUserRequest;
import com.prayerroster.service.dto.UserDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-facing user management: view, update profile/capabilities, activate/deactivate, assign role.
 * Every mutation evicts the affected user's cached authorities (DynamicAuthoritiesService) so a
 * status or role change takes effect immediately rather than waiting out the cache TTL.
 */
@Service
@Transactional
public class UserManagementService {

    private static final String ENTITY_NAME = "user";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DynamicAuthoritiesService authoritiesService;
    private final ApplicationEventPublisher eventPublisher;

    public UserManagementService(
        UserRepository userRepository,
        RoleRepository roleRepository,
        DynamicAuthoritiesService authoritiesService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.authoritiesService = authoritiesService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public Page<UserDTO> findAll(Pageable pageable) {
        return userRepository.findAllWithRole(pageable).map(UserDTO::from);
    }

    @Transactional(readOnly = true)
    public UserDTO findOne(String id) {
        return UserDTO.from(getExisting(id));
    }

    public UserDTO update(String id, UpdateUserRequest request) {
        User user = getExisting(id);
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setCanModerate(request.canModerate());
        user.setCanPreach(request.canPreach());
        User saved = userRepository.save(user);
        authoritiesService.evict(id);
        return UserDTO.from(saved);
    }

    public UserDTO updateStatus(String id, boolean active) {
        User user = getExisting(id);
        boolean wasActive = user.isActive();
        user.setActive(active);
        User saved = userRepository.save(user);
        authoritiesService.evict(id);
        if (wasActive && !active) {
            eventPublisher.publishEvent(new UserDeactivatedEvent(id));
        }
        return UserDTO.from(saved);
    }

    public UserDTO assignRole(String id, String roleName) {
        User user = getExisting(id);
        Role role = roleRepository.findByName(roleName).orElseThrow(() -> new BadRequestAlertException("Role not found: " + roleName, ENTITY_NAME, "roleNotFound")
        );
        user.setRole(role);
        User saved = userRepository.save(user);
        authoritiesService.evict(id);
        return UserDTO.from(saved);
    }

    private User getExisting(String id) {
        return userRepository.findByIdWithRole(id).orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }
}

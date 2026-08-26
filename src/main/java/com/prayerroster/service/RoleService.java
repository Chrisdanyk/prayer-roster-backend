package com.prayerroster.service;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Administration of the business role graph. Every write evicts the whole authorities cache, because
 * a role's permission change affects every user holding it and {@code evict(userId)} is per-user -
 * without this the change would appear to do nothing until the TTL expired.
 * <p>
 * The eviction is registered as an {@code afterCommit} {@link TransactionSynchronization} rather than
 * called inline. Calling it inline, still inside this method's transaction, would let a request that
 * arrives between the eviction and the commit recompute authorities from the pre-commit rows and
 * re-cache that stale set for the full TTL - the exact bug this eviction exists to prevent, made
 * cache-wide instead of per-user. See docs/sprint-roadmap.md (Sprint 6) for the transaction-timing
 * bug this codebase has already been bitten by.
 * <p>
 * The guards here each prevent a way to lock everyone out permanently. See
 * docs/phase1-architecture.md section 9.
 */
@Service
@Transactional
public class RoleService {

    private static final String ENTITY_NAME = "role";
    private static final Set<String> BASELINE_ROLES = Set.of(RoleNames.SUPER_ADMIN, RoleNames.ADMIN, RoleNames.USER);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final DynamicAuthoritiesService authoritiesService;

    public RoleService(
        RoleRepository roleRepository,
        PermissionRepository permissionRepository,
        UserRepository userRepository,
        DynamicAuthoritiesService authoritiesService
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.authoritiesService = authoritiesService;
    }

    @Transactional(readOnly = true)
    public List<RoleDTO> findAll() {
        return roleRepository
            .findAllWithPermissions()
            .stream()
            .map(role -> RoleDTO.from(role, userRepository.countByRoleId(role.getId())))
            .toList();
    }

    public RoleDTO create(CreateRoleRequest request) {
        if (roleRepository.findByName(request.name()).isPresent()) {
            throw new BadRequestAlertException("A role named " + request.name() + " already exists", ENTITY_NAME, "duplicateName");
        }
        Role role = new Role();
        role.setName(request.name());
        role.setDescription(request.description());
        role.setPermissions(resolvePermissions(request.permissionCodes()));
        Role saved = roleRepository.save(role);
        registerAfterCommitEviction();
        return RoleDTO.from(saved, 0L);
    }

    public RoleDTO update(Long id, UpdateRoleRequest request) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Role not found: " + id));
        applyRename(role, request.name());
        Set<Permission> permissions = resolvePermissions(request.permissionCodes());
        // Compared by permission code, not entity equality: Permission#equals is JPA-identity-based
        // (by id), so two instances representing the same permission but hydrated separately - one
        // attached to the role, one freshly resolved from the repository - are not guaranteed to
        // satisfy Set#containsAll even when they denote the same permission.
        Set<String> newCodes = permissions.stream().map(Permission::getCode).collect(Collectors.toSet());
        Set<String> currentCodes = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
        if (RoleNames.SUPER_ADMIN.equals(role.getName()) && !newCodes.containsAll(currentCodes)) {
            throw new BadRequestAlertException(
                "SUPER_ADMIN cannot have permissions removed - it is the recovery path",
                ENTITY_NAME,
                "superAdminImmutable"
            );
        }
        role.setDescription(request.description());
        role.setPermissions(permissions);
        Role saved = roleRepository.save(role);
        registerAfterCommitEviction();
        return RoleDTO.from(saved, userRepository.countByRoleId(id));
    }

    public void delete(Long id) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Role not found: " + id));
        if (BASELINE_ROLES.contains(role.getName())) {
            throw new BadRequestAlertException("Baseline roles cannot be deleted", ENTITY_NAME, "baselineRole");
        }
        long holders = userRepository.countByRoleId(id);
        if (holders > 0) {
            throw new BadRequestAlertException(holders + " user(s) still hold this role", ENTITY_NAME, "roleInUse");
        }
        roleRepository.delete(role);
        registerAfterCommitEviction();
    }

    /**
     * {@code null} or unchanged leaves the name alone. Renaming one of the three baseline roles
     * (SUPER_ADMIN/ADMIN/USER) is rejected outright - custom roles remain renameable. A name already
     * held by a different role is rejected the same way {@link #create} rejects a duplicate on
     * insert.
     */
    private void applyRename(Role role, String newName) {
        if (newName == null || newName.equals(role.getName())) {
            return;
        }
        if (BASELINE_ROLES.contains(role.getName())) {
            throw new BadRequestAlertException("Baseline roles cannot be renamed", ENTITY_NAME, "baselineRole");
        }
        if (roleRepository.findByName(newName).isPresent()) {
            throw new BadRequestAlertException("A role named " + newName + " already exists", ENTITY_NAME, "duplicateName");
        }
        role.setName(newName);
    }

    /**
     * Registered rather than called inline so the cache-wide eviction happens only once this
     * transaction has actually committed - see the class Javadoc.
     */
    private void registerAfterCommitEviction() {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    authoritiesService.evictAll();
                }
            }
        );
    }

    private Set<Permission> resolvePermissions(List<String> codes) {
        Set<Permission> permissions = new HashSet<>();
        if (codes == null) {
            return permissions;
        }
        for (String code : codes) {
            permissions.add(
                permissionRepository
                    .findByCode(code)
                    .orElseThrow(() -> new BadRequestAlertException("Unknown permission code: " + code, ENTITY_NAME, "unknownPermission"))
            );
        }
        return permissions;
    }
}

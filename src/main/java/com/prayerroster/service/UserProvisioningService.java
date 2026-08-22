package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.RoleNames;
import com.prayerroster.security.oauth2.GoogleIdentity;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads or provisions the local {@link User} for a validated Google identity. Called on every
 * authenticated request's authority resolution (see {@code DynamicAuthoritiesService}), so the
 * fast path (existing, unchanged user) does exactly one read and no write.
 * <p>
 * The SUPER_ADMIN bootstrap check ({@code application.security.initial-super-admin-email}) only
 * ever runs at row creation - never re-evaluated on later logins, so a later manual demotion of
 * that account is never silently undone by a restart. See docs/phase1-architecture.md section 10.
 */
@Service
public class UserProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ApplicationProperties applicationProperties;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UserProvisioningService(
        UserRepository userRepository,
        RoleRepository roleRepository,
        ApplicationProperties applicationProperties,
        PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.applicationProperties = applicationProperties;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User provisionOrRefresh(GoogleIdentity identity) {
        return userRepository.findByIdWithRoleAndPermissions(identity.sub()).map(user -> refreshProfileIfChanged(user, identity)).orElseGet(
            () -> createUserSafely(identity)
        );
    }

    private User refreshProfileIfChanged(User user, GoogleIdentity identity) {
        boolean changed = false;
        if (!Objects.equals(user.getEmail(), identity.email())) {
            user.setEmail(identity.email());
            changed = true;
        }
        if (!Objects.equals(user.getFirstName(), identity.firstName())) {
            user.setFirstName(identity.firstName());
            changed = true;
        }
        if (!Objects.equals(user.getLastName(), identity.lastName())) {
            user.setLastName(identity.lastName());
            changed = true;
        }
        return changed ? userRepository.save(user) : user;
    }

    /**
     * Creates the user in its own transaction (REQUIRES_NEW via {@link TransactionTemplate}, not an
     * annotation, to avoid Spring AOP's self-invocation trap) so that a lost race against a
     * concurrent first-login for the same brand-new user - e.g. a SPA firing two parallel API calls
     * right after authenticating - fails in isolation and falls back to reading the winner's row,
     * instead of poisoning the caller's own transaction.
     */
    private User createUserSafely(GoogleIdentity identity) {
        try {
            return requiresNewTransactionTemplate.execute(status -> buildAndSaveNewUser(identity));
        } catch (DataIntegrityViolationException concurrentCreateWon) {
            LOG.debug("Lost the provisioning race for user {}, reading the winner's row", identity.sub());
            return userRepository.findByIdWithRoleAndPermissions(identity.sub()).orElseThrow(() -> concurrentCreateWon);
        }
    }

    private User buildAndSaveNewUser(GoogleIdentity identity) {
        User user = new User();
        user.setId(identity.sub());
        user.setEmail(identity.email());
        user.setFirstName(identity.firstName());
        user.setLastName(identity.lastName());
        user.setActive(true);
        user.setLangKey("fr");
        user.setRole(resolveInitialRole(identity.email()));
        User saved = userRepository.save(user);
        LOG.info("Provisioned new user with role {}", saved.getRole().getName());
        return saved;
    }

    private Role resolveInitialRole(String email) {
        String bootstrapEmail = applicationProperties.getSecurity().getInitialSuperAdminEmail();
        String roleName = bootstrapEmail != null && bootstrapEmail.equalsIgnoreCase(email) ? RoleNames.SUPER_ADMIN : RoleNames.USER;
        return roleRepository.findByNameWithPermissions(roleName).orElseThrow(() -> new IllegalStateException(
            "Role not seeded: " + roleName
        ));
    }
}

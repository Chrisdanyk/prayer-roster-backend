package com.prayerroster.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prayerroster.domain.User;
import com.prayerroster.security.oauth2.GoogleIdentity;
import com.prayerroster.service.UserProvisioningService;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Resolves the dynamic {@code PERM_*} (and, for SUPER_ADMIN, {@code ROLE_ADMIN}) authorities for an
 * authenticated Google identity, provisioning the local {@link User} row on first sight.
 * <p>
 * Cached for a short TTL (not indefinitely) so that a change an admin makes to a role's permissions
 * takes effect within seconds without a DB round trip on every single request - see
 * docs/phase1-architecture.md section 9. {@link #evict(String)} lets a future admin
 * role/permission-management endpoint force immediate consistency for one user.
 */
@Service
public class DynamicAuthoritiesService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final UserProvisioningService provisioningService;
    private final Cache<String, Set<GrantedAuthority>> authoritiesCache;

    public DynamicAuthoritiesService(UserProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
        this.authoritiesCache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).maximumSize(10_000).build();
    }

    public Set<GrantedAuthority> resolveAuthorities(GoogleIdentity identity) {
        return authoritiesCache.get(identity.sub(), sub -> computeAuthorities(provisioningService.provisionOrRefresh(identity)));
    }

    public void evict(String userId) {
        authoritiesCache.invalidate(userId);
    }

    /**
     * Editing a role changes the authorities of every user holding it, and {@link #evict(String)} is
     * per-user - without this a permission change appears to do nothing for up to the cache TTL.
     */
    public void evictAll() {
        authoritiesCache.invalidateAll();
    }

    private Set<GrantedAuthority> computeAuthorities(User user) {
        Set<GrantedAuthority> authorities = user
            .getRole()
            .getPermissions()
            .stream()
            .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(PermissionAuthorities.of(permission.getCode())))
            .collect(Collectors.toCollection(HashSet::new));
        if (RoleNames.SUPER_ADMIN.equals(user.getRole().getName())) {
            authorities.add(new SimpleGrantedAuthority(AuthoritiesConstants.ADMIN));
        }
        return authorities;
    }
}

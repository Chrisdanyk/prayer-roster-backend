package com.prayerroster.security;

/**
 * Converts a database {@link com.prayerroster.domain.Permission} code into the Spring Security
 * {@code GrantedAuthority} string checked by {@code @PreAuthorize("hasAuthority(...)")}. Kept
 * distinct from {@link AuthoritiesConstants}' static {@code ROLE_*} strings, which gate only
 * JHipster's own actuator console - see docs/phase1-architecture.md section 9.
 */
public final class PermissionAuthorities {

    public static final String PREFIX = "PERM_";

    private PermissionAuthorities() {}

    public static String of(String permissionCode) {
        return PREFIX + permissionCode;
    }
}

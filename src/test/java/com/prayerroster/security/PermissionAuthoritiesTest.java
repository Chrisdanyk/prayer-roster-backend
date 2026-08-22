package com.prayerroster.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionAuthoritiesTest {

    @Test
    void of_prefixesThePermissionCode() {
        assertThat(PermissionAuthorities.of("ROSTER_GENERATE")).isEqualTo("PERM_ROSTER_GENERATE");
    }
}

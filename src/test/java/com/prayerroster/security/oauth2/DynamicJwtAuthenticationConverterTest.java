package com.prayerroster.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prayerroster.security.DynamicAuthoritiesService;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class DynamicJwtAuthenticationConverterTest {

    @Mock
    private DynamicAuthoritiesService authoritiesService;

    @Test
    void convert_setsPrincipalNameToSubAndAuthoritiesFromResolvedSet() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("108234567890123456789")
            .claim("sub", "108234567890123456789")
            .claim("email", "jean@example.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
        Set<GrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("PERM_ROSTER_GENERATE"));
        when(authoritiesService.resolveAuthorities(GoogleIdentity.fromClaims(jwt.getClaims()))).thenReturn(authorities);

        AbstractAuthenticationToken token = new DynamicJwtAuthenticationConverter(authoritiesService).convert(jwt);

        assertThat(token.getName()).isEqualTo("108234567890123456789");
        assertThat(token.getAuthorities()).containsExactlyElementsOf(authorities);
    }
}

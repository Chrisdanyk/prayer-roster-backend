package com.prayerroster.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GoogleIdentityTest {

    @Test
    void fromClaims_extractsTheFourIdentityClaims() {
        Map<String, Object> claims = Map.of(
            "sub",
            "108",
            "email",
            "jean@example.com",
            "given_name",
            "Jean",
            "family_name",
            "Dupont",
            "email_verified",
            true
        );

        GoogleIdentity identity = GoogleIdentity.fromClaims(claims);

        assertThat(identity.sub()).isEqualTo("108");
        assertThat(identity.email()).isEqualTo("jean@example.com");
        assertThat(identity.firstName()).isEqualTo("Jean");
        assertThat(identity.lastName()).isEqualTo("Dupont");
    }
}

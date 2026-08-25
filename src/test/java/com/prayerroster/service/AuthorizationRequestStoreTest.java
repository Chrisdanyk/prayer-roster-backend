package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationRequestStoreTest {

    private AuthorizationRequestStore store;

    @BeforeEach
    void setUp() {
        store = new AuthorizationRequestStore();
    }

    @Test
    void create_producesAUniqueStateEachTime() {
        assertThat(store.create().state()).isNotEqualTo(store.create().state());
    }

    @Test
    void create_derivesAnS256ChallengeFromTheVerifier() {
        PendingAuthorization pending = store.create();

        // Base64url, unpadded: a SHA-256 digest is always 43 characters.
        assertThat(pending.codeChallenge()).hasSize(43).doesNotContain("=", "+", "/");
        assertThat(pending.codeVerifier()).isNotEqualTo(pending.codeChallenge());
    }

    @Test
    void consume_returnsTheVerifierForAKnownState() {
        PendingAuthorization pending = store.create();

        assertThat(store.consume(pending.state())).isEqualTo(pending.codeVerifier());
    }

    @Test
    void consume_rejectsAReplayedState() {
        PendingAuthorization pending = store.create();
        store.consume(pending.state());

        assertThatThrownBy(() -> store.consume(pending.state())).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void consume_rejectsAnUnknownState() {
        assertThatThrownBy(() -> store.consume("never-issued")).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void consume_rejectsAMissingState() {
        // Google's callback can arrive with no state parameter at all; the cache would NPE on a
        // null key, so it must be rejected as an invalid request rather than a server error.
        assertThatThrownBy(() -> store.consume(null)).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void create_failsLoudlyIfTheDigestAlgorithmIsUnavailable() {
        AuthorizationRequestStore broken = new AuthorizationRequestStore("NO-SUCH-ALGORITHM");

        assertThatThrownBy(broken::create).isInstanceOf(IllegalStateException.class);
    }
}

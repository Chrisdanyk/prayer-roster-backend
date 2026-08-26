package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandoffStoreTest {

    private static final GoogleTokenResponse TOKEN = new GoogleTokenResponse("id-token", 3599);

    private HandoffStore store;

    @BeforeEach
    void setUp() {
        store = new HandoffStore();
    }

    @Test
    void issue_returnsAnOpaqueValueThatIsNotTheToken() {
        String handoff = store.issue(TOKEN);

        assertThat(handoff).isNotBlank().doesNotContain("id-token");
    }

    @Test
    void issue_producesAUniqueValueEachTime() {
        assertThat(store.issue(TOKEN)).isNotEqualTo(store.issue(TOKEN));
    }

    @Test
    void redeem_returnsTheTokenForAKnownHandoff() {
        String handoff = store.issue(TOKEN);

        assertThat(store.redeem(handoff)).isEqualTo(TOKEN);
    }

    @Test
    void redeem_rejectsAReplayedHandoff() {
        String handoff = store.issue(TOKEN);
        store.redeem(handoff);

        assertThatThrownBy(() -> store.redeem(handoff)).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void redeem_rejectsAnUnknownHandoff() {
        assertThatThrownBy(() -> store.redeem("never-issued")).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void redeem_rejectsAMissingHandoff() {
        assertThatThrownBy(() -> store.redeem(null)).isInstanceOf(BadRequestAlertException.class);
    }
}

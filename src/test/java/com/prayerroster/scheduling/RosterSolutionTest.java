package com.prayerroster.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import java.util.List;
import org.junit.jupiter.api.Test;

class RosterSolutionTest {

    @Test
    void gettersReturnWhatTheConstructorWasGiven() {
        User user = new User();
        user.setId("u1");
        UserAvailability unavailability = new UserAvailability();
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(1L);

        RosterSolution solution = new RosterSolution(List.of(user), List.of(unavailability), List.of(assignment));
        solution.setScore(HardSoftScore.of(0, -3));

        assertThat(solution.getEligibleUsers()).containsExactly(user);
        assertThat(solution.getUnavailabilities()).containsExactly(unavailability);
        assertThat(solution.getAssignments()).containsExactly(assignment);
        assertThat(solution.getScore()).isEqualTo(HardSoftScore.of(0, -3));
    }
}

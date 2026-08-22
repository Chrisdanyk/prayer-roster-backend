package com.prayerroster.scheduling;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import java.util.List;

/**
 * The Timefold planning problem for one roster generation solve (see
 * docs/phase1-architecture.md section 6). {@code eligibleUsers} is pre-filtered to active users
 * with at least one capability before the solve even starts - inactive users never enter the value
 * range at all, with {@code inactiveUserCannotBeAssigned} kept as a defensive constraint in case of
 * stale data. {@code unavailabilities} can't be pre-filtered the same way since it's per-date, so
 * it's a plain problem fact joined against in {@link RosterConstraintProvider}.
 */
@PlanningSolution
public class RosterSolution {

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "userRange")
    private List<User> eligibleUsers;

    @ProblemFactCollectionProperty
    private List<UserAvailability> unavailabilities;

    @PlanningEntityCollectionProperty
    private List<PrayerAssignment> assignments;

    @PlanningScore
    private HardSoftScore score;

    private RosterSolution() {
        // No-arg constructor required by Timefold for solution cloning.
    }

    public RosterSolution(List<User> eligibleUsers, List<UserAvailability> unavailabilities, List<PrayerAssignment> assignments) {
        this.eligibleUsers = eligibleUsers;
        this.unavailabilities = unavailabilities;
        this.assignments = assignments;
    }

    public List<User> getEligibleUsers() {
        return eligibleUsers;
    }

    public List<UserAvailability> getUnavailabilities() {
        return unavailabilities;
    }

    public List<PrayerAssignment> getAssignments() {
        return assignments;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}

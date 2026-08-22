package com.prayerroster.scheduling;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import com.prayerroster.domain.UserAvailabilityStatus;
import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RosterConstraintProviderTest {

    private final ConstraintVerifier<RosterConstraintProvider, RosterSolution> constraintVerifier = ConstraintVerifier.build(
        new RosterConstraintProvider(),
        RosterSolution.class,
        PrayerAssignment.class
    );

    private static User user(String id, boolean active, boolean canModerate, boolean canPreach) {
        User user = new User();
        user.setId(id);
        user.setActive(active);
        user.setCanModerate(canModerate);
        user.setCanPreach(canPreach);
        return user;
    }

    private static PrayerSession session(Long id, LocalDate date) {
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(date);
        session.setDayOfWeek(date.getDayOfWeek());
        return session;
    }

    private static PrayerAssignment assignment(Long id, PrayerSession session, PrayerAssignmentRole role, User user) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setSession(session);
        assignment.setRole(role);
        assignment.setUser(user);
        return assignment;
    }

    @Test
    void everyAssignmentMustBeFilled_penalizesUnfilledAssignment() {
        PrayerAssignment unfilled = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, null);

        constraintVerifier.verifyThat(RosterConstraintProvider::everyAssignmentMustBeFilled).given(unfilled).penalizesBy(1);
    }

    @Test
    void everyAssignmentMustBeFilled_doesNotPenalizeFilledAssignment() {
        User user = user("u1", true, true, true);
        PrayerAssignment filled = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::everyAssignmentMustBeFilled).given(filled).penalizesBy(0);
    }

    @Test
    void moderatorAndPreacherMustDiffer_penalizesSameUserBothRolesSameSession() {
        User user = user("u1", true, true, true);
        PrayerSession session = session(1L, LocalDate.of(2026, 9, 6));
        PrayerAssignment moderator = assignment(1L, session, PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment preacher = assignment(2L, session, PrayerAssignmentRole.PREACHER, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::moderatorAndPreacherMustDiffer).given(moderator, preacher).penalizesBy(1);
    }

    @Test
    void moderatorAndPreacherMustDiffer_doesNotPenalizeDifferentUsers() {
        PrayerSession session = session(1L, LocalDate.of(2026, 9, 6));
        PrayerAssignment moderator = assignment(1L, session, PrayerAssignmentRole.MODERATOR, user("u1", true, true, true));
        PrayerAssignment preacher = assignment(2L, session, PrayerAssignmentRole.PREACHER, user("u2", true, true, true));

        constraintVerifier.verifyThat(RosterConstraintProvider::moderatorAndPreacherMustDiffer).given(moderator, preacher).penalizesBy(0);
    }

    @Test
    void preacherAtMostOncePerIsoWeek_penalizesSecondPreachInSameWeek() {
        User user = user("u1", true, true, true);
        // 2026-08-31 (Mon) and 2026-09-06 (Sun) both fall in ISO week 36.
        PrayerAssignment first = assignment(1L, session(1L, LocalDate.of(2026, 8, 31)), PrayerAssignmentRole.PREACHER, user);
        PrayerAssignment second = assignment(2L, session(2L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::preacherAtMostOncePerIsoWeek).given(first, second).penalizesBy(1);
    }

    @Test
    void preacherAtMostOncePerIsoWeek_doesNotPenalizeDifferentWeeksOrModeratorRole() {
        User user = user("u1", true, true, true);
        PrayerAssignment first = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user);
        PrayerAssignment second = assignment(2L, session(2L, LocalDate.of(2026, 9, 13)), PrayerAssignmentRole.PREACHER, user);
        PrayerAssignment moderator = assignment(3L, session(3L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);

        constraintVerifier
            .verifyThat(RosterConstraintProvider::preacherAtMostOncePerIsoWeek)
            .given(first, second, moderator)
            .penalizesBy(0);
    }

    @Test
    void inactiveUserCannotBeAssigned_penalizesInactiveUser() {
        PrayerAssignment assignment = assignment(
            1L,
            session(1L, LocalDate.of(2026, 9, 6)),
            PrayerAssignmentRole.MODERATOR,
            user("u1", false, true, true)
        );

        constraintVerifier.verifyThat(RosterConstraintProvider::inactiveUserCannotBeAssigned).given(assignment).penalizesBy(1);
    }

    @Test
    void inactiveUserCannotBeAssigned_doesNotPenalizeActiveUser() {
        PrayerAssignment active = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user("u1", true, true, true));

        constraintVerifier.verifyThat(RosterConstraintProvider::inactiveUserCannotBeAssigned).given(active).penalizesBy(0);
    }

    @Test
    void unavailableUserCannotBeAssigned_penalizesOverlappingUnavailability() {
        User user = user("u1", true, true, true);
        PrayerAssignment assignment = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);
        UserAvailability unavailability = new UserAvailability();
        unavailability.setUser(user);
        unavailability.setStartDate(LocalDate.of(2026, 9, 5));
        unavailability.setEndDate(LocalDate.of(2026, 9, 7));
        unavailability.setStatus(UserAvailabilityStatus.ACTIVE);

        constraintVerifier
            .verifyThat(RosterConstraintProvider::unavailableUserCannotBeAssigned)
            .given(assignment, unavailability)
            .penalizesBy(1);
    }

    @Test
    void unavailableUserCannotBeAssigned_doesNotPenalizeNonOverlapping() {
        User user = user("u1", true, true, true);
        // One assignment before the unavailability window, one after - covers both non-overlap directions.
        PrayerAssignment before = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment after = assignment(2L, session(2L, LocalDate.of(2026, 9, 15)), PrayerAssignmentRole.MODERATOR, user);
        UserAvailability unavailability = new UserAvailability();
        unavailability.setUser(user);
        unavailability.setStartDate(LocalDate.of(2026, 9, 10));
        unavailability.setEndDate(LocalDate.of(2026, 9, 12));
        unavailability.setStatus(UserAvailabilityStatus.ACTIVE);

        constraintVerifier
            .verifyThat(RosterConstraintProvider::unavailableUserCannotBeAssigned)
            .given(before, after, unavailability)
            .penalizesBy(0);
    }

    @Test
    void userMustHaveModerationCapability_penalizesWhenCannotModerate() {
        PrayerAssignment assignment = assignment(
            1L,
            session(1L, LocalDate.of(2026, 9, 6)),
            PrayerAssignmentRole.MODERATOR,
            user("u1", true, false, true)
        );

        constraintVerifier.verifyThat(RosterConstraintProvider::userMustHaveModerationCapability).given(assignment).penalizesBy(1);
    }

    @Test
    void userMustHaveModerationCapability_doesNotPenalizeCapableOrOtherRole() {
        PrayerAssignment capable = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user("u1", true, true, true));
        PrayerAssignment preacher = assignment(2L, session(2L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user("u2", true, false, true));

        constraintVerifier
            .verifyThat(RosterConstraintProvider::userMustHaveModerationCapability)
            .given(capable, preacher)
            .penalizesBy(0);
    }

    @Test
    void userMustHavePreachingCapability_penalizesWhenCannotPreach() {
        PrayerAssignment assignment = assignment(
            1L,
            session(1L, LocalDate.of(2026, 9, 6)),
            PrayerAssignmentRole.PREACHER,
            user("u1", true, true, false)
        );

        constraintVerifier.verifyThat(RosterConstraintProvider::userMustHavePreachingCapability).given(assignment).penalizesBy(1);
    }

    @Test
    void userMustHavePreachingCapability_doesNotPenalizeCapableOrOtherRole() {
        PrayerAssignment capable = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user("u1", true, true, true));
        PrayerAssignment moderator = assignment(2L, session(2L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user("u2", true, true, false));

        constraintVerifier
            .verifyThat(RosterConstraintProvider::userMustHavePreachingCapability)
            .given(capable, moderator)
            .penalizesBy(0);
    }

    @Test
    void balanceModerationAssignments_penalizesBySquaredCountTimesWeight() {
        User user = user("u1", true, true, true);
        PrayerAssignment first = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment second = assignment(2L, session(2L, LocalDate.of(2026, 9, 13)), PrayerAssignmentRole.MODERATOR, user);
        // Also exercise the "not a moderator assignment" branch of the filter.
        PrayerAssignment preacher = assignment(3L, session(3L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user);

        constraintVerifier
            .verifyThat(RosterConstraintProvider::balanceModerationAssignments)
            .given(first, second, preacher)
            .penalizesBy(12);
    }

    @Test
    void balancePreachingAssignments_penalizesBySquaredCountTimesWeight() {
        User user = user("u1", true, true, true);
        PrayerAssignment first = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user);
        PrayerAssignment second = assignment(2L, session(2L, LocalDate.of(2026, 9, 13)), PrayerAssignmentRole.PREACHER, user);
        PrayerAssignment moderator = assignment(3L, session(3L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);

        constraintVerifier
            .verifyThat(RosterConstraintProvider::balancePreachingAssignments)
            .given(first, second, moderator)
            .penalizesBy(12);
    }

    @Test
    void avoidConsecutiveAssignments_penalizesConsecutiveDates() {
        User user = user("u1", true, true, true);
        PrayerAssignment monday = assignment(1L, session(1L, LocalDate.of(2026, 9, 7)), PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment tuesday = assignment(2L, session(2L, LocalDate.of(2026, 9, 8)), PrayerAssignmentRole.MODERATOR, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::avoidConsecutiveAssignments).given(monday, tuesday).penalizesBy(1);
    }

    @Test
    void avoidConsecutiveAssignments_doesNotPenalizeNonConsecutiveAssignments() {
        User user = user("u1", true, true, true);
        PrayerAssignment monday = assignment(1L, session(1L, LocalDate.of(2026, 9, 7)), PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment thursday = assignment(2L, session(2L, LocalDate.of(2026, 9, 10)), PrayerAssignmentRole.MODERATOR, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::avoidConsecutiveAssignments).given(monday, thursday).penalizesBy(0);
    }

    @Test
    void minimizeAssignmentImbalance_penalizesBySquaredCountAcrossRoles() {
        User user = user("u1", true, true, true);
        PrayerAssignment moderator = assignment(1L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.MODERATOR, user);
        PrayerAssignment preacher = assignment(2L, session(1L, LocalDate.of(2026, 9, 6)), PrayerAssignmentRole.PREACHER, user);

        constraintVerifier.verifyThat(RosterConstraintProvider::minimizeAssignmentImbalance).given(moderator, preacher).penalizesBy(4);
    }
}

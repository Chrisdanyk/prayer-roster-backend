package com.prayerroster.scheduling;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.UserAvailability;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * Hard and soft constraints for one roster solve (see docs/phase1-architecture.md sections 7-8).
 * {@code noPreacherOnModerationOnlyDay} is deliberately absent here: it's structurally guaranteed by
 * generation (a moderation-only day never gets a PREACHER row at all), so there is nothing for the
 * solver to enforce.
 * <p>
 * Every constraint below except {@code everyAssignmentMustBeFilled} uses plain
 * {@code forEach(PrayerAssignment.class)}, which - per Timefold's contract for a class with a
 * genuine {@code @PlanningVariable} - only ever emits entities whose variable is already
 * initialized. That makes an explicit {@code user != null} check on those streams dead code, not
 * just redundant: it can never evaluate false, so it's omitted rather than tested around.
 * {@code everyAssignmentMustBeFilled} is the deliberate exception - it needs
 * {@code forEachIncludingUnassigned} specifically to see the unfilled ones.
 */
public class RosterConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            everyAssignmentMustBeFilled(factory),
            moderatorAndPreacherMustDiffer(factory),
            preacherAtMostOncePerIsoWeek(factory),
            inactiveUserCannotBeAssigned(factory),
            unavailableUserCannotBeAssigned(factory),
            userMustHaveModerationCapability(factory),
            userMustHavePreachingCapability(factory),
            balanceModerationAssignments(factory),
            balancePreachingAssignments(factory),
            avoidConsecutiveAssignments(factory),
            minimizeAssignmentImbalance(factory),
        };
    }

    Constraint everyAssignmentMustBeFilled(ConstraintFactory factory) {
        return factory
            .forEachIncludingUnassigned(PrayerAssignment.class)
            .filter(a -> a.getUser() == null)
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("everyAssignmentMustBeFilled");
    }

    Constraint moderatorAndPreacherMustDiffer(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.MODERATOR)
            .join(
                factory.forEach(PrayerAssignment.class).filter(a -> a.getRole() == PrayerAssignmentRole.PREACHER),
                Joiners.equal(PrayerAssignment::getSession),
                Joiners.equal(PrayerAssignment::getUser)
            )
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("moderatorAndPreacherMustDiffer");
    }

    Constraint preacherAtMostOncePerIsoWeek(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.PREACHER)
            .groupBy(PrayerAssignment::getUser, a -> isoWeekKey(a.getSession().getDate()), ConstraintCollectors.count())
            .filter((user, week, count) -> count > 1)
            .penalize(HardSoftScore.ONE_HARD, (user, week, count) -> count - 1)
            .asConstraint("preacherAtMostOncePerIsoWeek");
    }

    Constraint inactiveUserCannotBeAssigned(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> !a.getUser().isActive())
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("inactiveUserCannotBeAssigned");
    }

    Constraint unavailableUserCannotBeAssigned(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .join(factory.forEach(UserAvailability.class), Joiners.equal(a -> a.getUser().getId(), ua -> ua.getUser().getId()))
            .filter((a, ua) -> !a.getSession().getDate().isBefore(ua.getStartDate()) && !a.getSession().getDate().isAfter(ua.getEndDate()))
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("unavailableUserCannotBeAssigned");
    }

    Constraint userMustHaveModerationCapability(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.MODERATOR && !a.getUser().isCanModerate())
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("userMustHaveModerationCapability");
    }

    Constraint userMustHavePreachingCapability(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.PREACHER && !a.getUser().isCanPreach())
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("userMustHavePreachingCapability");
    }

    Constraint balanceModerationAssignments(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.MODERATOR)
            .groupBy(PrayerAssignment::getUser, ConstraintCollectors.count())
            .penalize(HardSoftScore.ONE_SOFT, (user, count) -> 3 * count * count)
            .asConstraint("balanceModerationAssignments");
    }

    Constraint balancePreachingAssignments(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .filter(a -> a.getRole() == PrayerAssignmentRole.PREACHER)
            .groupBy(PrayerAssignment::getUser, ConstraintCollectors.count())
            .penalize(HardSoftScore.ONE_SOFT, (user, count) -> 3 * count * count)
            .asConstraint("balancePreachingAssignments");
    }

    Constraint avoidConsecutiveAssignments(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .join(factory.forEach(PrayerAssignment.class), Joiners.equal(PrayerAssignment::getUser))
            .filter((a1, a2) -> a1.getSession().getDate().plusDays(1).equals(a2.getSession().getDate()))
            .penalize(HardSoftScore.ONE_SOFT)
            .asConstraint("avoidConsecutiveAssignments");
    }

    Constraint minimizeAssignmentImbalance(ConstraintFactory factory) {
        return factory
            .forEach(PrayerAssignment.class)
            .groupBy(PrayerAssignment::getUser, ConstraintCollectors.count())
            .penalize(HardSoftScore.ONE_SOFT, (user, count) -> count * count)
            .asConstraint("minimizeAssignmentImbalance");
    }

    private static long isoWeekKey(LocalDate date) {
        return (long) date.get(IsoFields.WEEK_BASED_YEAR) * 100 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}

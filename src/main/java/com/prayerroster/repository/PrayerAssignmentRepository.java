package com.prayerroster.repository;

import com.prayerroster.domain.PrayerAssignment;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrayerAssignmentRepository extends JpaRepository<PrayerAssignment, Long> {
    /**
     * Every assignment this user currently holds, in the given date range, on a roster that has
     * already been published - the candidate set for rescheduling detection (see
     * docs/phase1-architecture.md section 12). Join-fetched with session and roster in one query so
     * flagging {@code requiresRescheduling} and grouping by roster never N+1s.
     */
    @Query(
        "select distinct a from PrayerAssignment a " +
        "join fetch a.session s join fetch s.roster r " +
        "where a.user.id = :userId and s.date between :from and :to " +
        "and r.status = com.prayerroster.domain.RosterStatus.PUBLISHED"
    )
    List<PrayerAssignment> findPublishedAssignmentsForUserInRange(
        @Param("userId") String userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /**
     * Every filled assignment on a published roster for one specific date - the candidate set for
     * {@link com.prayerroster.service.ReminderService}'s daily sweep. Join-fetched with session and
     * user in one query so building reminder notifications never N+1s.
     */
    @Query(
        "select distinct a from PrayerAssignment a " +
        "join fetch a.session s join fetch a.user " +
        "where s.date = :date and s.roster.status = com.prayerroster.domain.RosterStatus.PUBLISHED"
    )
    List<PrayerAssignment> findPublishedAssignmentsForDate(@Param("date") LocalDate date);

    /**
     * Every assignment tagged with one generation run - the working set {@link
     * com.prayerroster.service.RosterSolvingService} solves and applies. {@code user} is left-joined,
     * not inner-joined: a brand-new generation's assignments are all still unfilled at this point.
     */
    @Query(
        "select distinct a from PrayerAssignment a " +
        "join fetch a.session s left join fetch a.user " +
        "where a.generation.id = :generationId " +
        "order by s.date, a.role"
    )
    List<PrayerAssignment> findByGenerationIdWithSessionAndUser(@Param("generationId") Long generationId);
}

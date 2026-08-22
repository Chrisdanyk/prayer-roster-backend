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
}

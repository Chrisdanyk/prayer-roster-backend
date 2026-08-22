package com.prayerroster.repository;

import com.prayerroster.domain.UserAvailability;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAvailabilityRepository extends JpaRepository<UserAvailability, Long> {
    List<UserAvailability> findByUserIdOrderByStartDateDesc(String userId);

    Optional<UserAvailability> findByIdAndUserId(Long id, String userId);

    /**
     * Any ACTIVE record for this user overlapping [startDate, endDate], optionally excluding one
     * record (used when checking an update against the record being updated).
     */
    @Query(
        "select ua from UserAvailability ua " +
        "where ua.user.id = :userId and ua.status = com.prayerroster.domain.UserAvailabilityStatus.ACTIVE " +
        "and ua.startDate <= :endDate and ua.endDate >= :startDate " +
        "and (:excludeId is null or ua.id <> :excludeId)"
    )
    List<UserAvailability> findOverlapping(
        @Param("userId") String userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("excludeId") Long excludeId
    );

    /**
     * Every ACTIVE availability record overlapping a planning period, in one query - the Timefold
     * solve needs this as a bulk problem-fact list, never fetched per-user (which would be N+1 over
     * the eligible-user list).
     */
    @Query(
        "select ua from UserAvailability ua join fetch ua.user " +
        "where ua.status = com.prayerroster.domain.UserAvailabilityStatus.ACTIVE " +
        "and ua.startDate <= :to and ua.endDate >= :from"
    )
    List<UserAvailability> findActiveOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);
}

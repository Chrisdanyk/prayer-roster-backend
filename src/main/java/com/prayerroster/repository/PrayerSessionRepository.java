package com.prayerroster.repository;

import com.prayerroster.domain.PrayerSession;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrayerSessionRepository extends JpaRepository<PrayerSession, Long> {
    boolean existsByDateBetween(LocalDate from, LocalDate to);

    @Query(
        "select distinct s from PrayerSession s " +
        "left join fetch s.assignments a left join fetch a.user " +
        "where s.date between :from and :to order by s.date"
    )
    List<PrayerSession> findByDateBetweenWithAssignments(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select distinct s from PrayerSession s left join fetch s.assignments a left join fetch a.user where s.id = :id")
    Optional<PrayerSession> findByIdWithAssignments(@Param("id") Long id);

    @Query(
        "select distinct s from PrayerSession s " +
        "left join fetch s.assignments a left join fetch a.user " +
        "where s.roster.id = :rosterId order by s.date"
    )
    List<PrayerSession> findByRosterIdWithAssignments(@Param("rosterId") Long rosterId);
}

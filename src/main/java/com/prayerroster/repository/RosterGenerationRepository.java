package com.prayerroster.repository;

import com.prayerroster.domain.RosterGeneration;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterGenerationRepository extends JpaRepository<RosterGeneration, Long> {
    /** Explicit rather than derived - see the Sprint 7 note in CLAUDE.md. */
    @Query("select g from RosterGeneration g where g.roster.id = :rosterId order by g.createdDate desc")
    List<RosterGeneration> findByRosterIdMostRecentFirst(@Param("rosterId") Long rosterId);
}

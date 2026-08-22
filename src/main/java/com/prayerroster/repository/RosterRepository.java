package com.prayerroster.repository;

import com.prayerroster.domain.Roster;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RosterRepository extends JpaRepository<Roster, Long> {
    /**
     * The furthest-future date currently covered by any generated roster - deliberately unfiltered
     * by status, since {@link com.prayerroster.service.RosterGenerationService#generate} rejects any
     * overlap by {@code PrayerSession.date} regardless of the owning roster's status (even
     * {@code ARCHIVED}). The rolling generation job (see docs/phase1-architecture.md section 1) walks
     * forward from here.
     */
    Optional<Roster> findTopByOrderByPeriodToDesc();
}

package com.prayerroster.service;

import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.service.dto.UpcomingAssignmentDTO;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service read of a user's own upcoming assignments (see docs/phase1-architecture.md section
 * 14) - both the JSON listing and the calendar PDF (Sprint 8) read through here, so the "upcoming"
 * window is defined in exactly one place.
 */
@Service
@Transactional(readOnly = true)
public class PrayerAssignmentService {

    /**
     * How far ahead "upcoming" looks. Generous relative to the ~2-month rolling generation horizon
     * (see docs/phase1-architecture.md section 1), so a manually-generated custom-range roster
     * further out is never silently excluded, without resorting to an unbounded query.
     */
    static final int UPCOMING_HORIZON_MONTHS = 6;

    private final PrayerAssignmentRepository prayerAssignmentRepository;

    public PrayerAssignmentService(PrayerAssignmentRepository prayerAssignmentRepository) {
        this.prayerAssignmentRepository = prayerAssignmentRepository;
    }

    public List<UpcomingAssignmentDTO> findOwnUpcoming(String userId) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusMonths(UPCOMING_HORIZON_MONTHS);
        return prayerAssignmentRepository
            .findPublishedAssignmentsForUserInRange(userId, from, to)
            .stream()
            .sorted(Comparator.comparing(a -> a.getSession().getDate()))
            .map(UpcomingAssignmentDTO::from)
            .toList();
    }
}

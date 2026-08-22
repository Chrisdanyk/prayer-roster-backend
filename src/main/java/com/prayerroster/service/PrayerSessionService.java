package com.prayerroster.service;

import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.service.dto.PrayerSessionDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PrayerSessionService {

    private static final String ENTITY_NAME = "prayerSession";

    private final PrayerSessionRepository prayerSessionRepository;

    public PrayerSessionService(PrayerSessionRepository prayerSessionRepository) {
        this.prayerSessionRepository = prayerSessionRepository;
    }

    public List<PrayerSessionDTO> findByDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadRequestAlertException("from must not be after to", ENTITY_NAME, "invalidPeriod");
        }
        return prayerSessionRepository.findByDateBetweenWithAssignments(from, to).stream().map(PrayerSessionDTO::from).toList();
    }

    public PrayerSessionDTO findOne(Long id) {
        return prayerSessionRepository
            .findByIdWithAssignments(id)
            .map(PrayerSessionDTO::from)
            .orElseThrow(() -> new EntityNotFoundException("Prayer session not found: " + id));
    }
}

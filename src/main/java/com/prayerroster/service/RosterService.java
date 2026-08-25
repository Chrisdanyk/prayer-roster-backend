package com.prayerroster.service;

import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.service.dto.RosterGenerationDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RosterService {

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository generationRepository;

    public RosterService(RosterRepository rosterRepository, RosterGenerationRepository generationRepository) {
        this.rosterRepository = rosterRepository;
        this.generationRepository = generationRepository;
    }

    public Page<RosterDTO> findAll(Pageable pageable) {
        return rosterRepository.findAll(pageable).map(RosterDTO::from);
    }

    public RosterDTO findOne(Long id) {
        return rosterRepository.findById(id).map(RosterDTO::from).orElseThrow(() -> new EntityNotFoundException("Roster not found: " + id)
        );
    }

    public List<RosterGenerationDTO> findGenerations(Long rosterId) {
        if (!rosterRepository.existsById(rosterId)) {
            throw new EntityNotFoundException("Roster not found: " + rosterId);
        }
        return generationRepository.findByRosterIdMostRecentFirst(rosterId).stream().map(RosterGenerationDTO::from).toList();
    }
}

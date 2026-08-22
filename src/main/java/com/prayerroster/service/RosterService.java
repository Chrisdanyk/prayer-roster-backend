package com.prayerroster.service;

import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RosterService {

    private final RosterRepository rosterRepository;

    public RosterService(RosterRepository rosterRepository) {
        this.rosterRepository = rosterRepository;
    }

    public Page<RosterDTO> findAll(Pageable pageable) {
        return rosterRepository.findAll(pageable).map(RosterDTO::from);
    }

    public RosterDTO findOne(Long id) {
        return rosterRepository.findById(id).map(RosterDTO::from).orElseThrow(() -> new EntityNotFoundException("Roster not found: " + id)
        );
    }
}

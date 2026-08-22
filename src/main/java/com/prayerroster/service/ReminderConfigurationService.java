package com.prayerroster.service;

import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.repository.ReminderConfigurationRepository;
import com.prayerroster.service.dto.ReminderConfigurationDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of the global reminder offsets (see docs/phase1-architecture.md section 13). */
@Service
@Transactional
public class ReminderConfigurationService {

    private static final String ENTITY_NAME = "reminderConfiguration";

    private final ReminderConfigurationRepository reminderConfigurationRepository;

    public ReminderConfigurationService(ReminderConfigurationRepository reminderConfigurationRepository) {
        this.reminderConfigurationRepository = reminderConfigurationRepository;
    }

    @Transactional(readOnly = true)
    public List<ReminderConfigurationDTO> findAll() {
        return reminderConfigurationRepository.findAll().stream().map(ReminderConfigurationDTO::from).toList();
    }

    public ReminderConfigurationDTO create(Integer daysBefore) {
        if (reminderConfigurationRepository.existsByDaysBefore(daysBefore)) {
            throw new BadRequestAlertException("A reminder offset of " + daysBefore + " day(s) already exists", ENTITY_NAME, "duplicateOffset");
        }
        ReminderConfiguration configuration = new ReminderConfiguration();
        configuration.setDaysBefore(daysBefore);
        configuration.setActive(true);
        return ReminderConfigurationDTO.from(reminderConfigurationRepository.save(configuration));
    }

    public ReminderConfigurationDTO updateActive(Long id, boolean active) {
        ReminderConfiguration configuration = reminderConfigurationRepository.findById(id).orElseThrow(() -> new EntityNotFoundException(
            "Reminder configuration not found: " + id
        ));
        configuration.setActive(active);
        return ReminderConfigurationDTO.from(reminderConfigurationRepository.save(configuration));
    }
}

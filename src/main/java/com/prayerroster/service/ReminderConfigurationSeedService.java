package com.prayerroster.service;

import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.repository.ReminderConfigurationRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default reminder offsets ([7, 1] days before) on first startup only - idempotent, and
 * (like {@link RbacSeedService}) never overwrites an admin's later customization via the API (see
 * docs/phase1-architecture.md section 13).
 */
@Service
public class ReminderConfigurationSeedService implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReminderConfigurationSeedService.class);
    private static final List<Integer> DEFAULT_OFFSETS = List.of(7, 1);

    private final ReminderConfigurationRepository reminderConfigurationRepository;

    public ReminderConfigurationSeedService(ReminderConfigurationRepository reminderConfigurationRepository) {
        this.reminderConfigurationRepository = reminderConfigurationRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (reminderConfigurationRepository.count() > 0) {
            return;
        }
        for (Integer daysBefore : DEFAULT_OFFSETS) {
            ReminderConfiguration configuration = new ReminderConfiguration();
            configuration.setDaysBefore(daysBefore);
            configuration.setActive(true);
            reminderConfigurationRepository.save(configuration);
        }
        LOG.info("Reminder offsets seeded: {}", DEFAULT_OFFSETS);
    }
}

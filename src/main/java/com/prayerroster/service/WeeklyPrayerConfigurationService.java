package com.prayerroster.service;

import com.prayerroster.domain.WeeklyPrayerConfiguration;
import com.prayerroster.domain.WeeklyPrayerConfigurationDay;
import com.prayerroster.repository.WeeklyPrayerConfigurationRepository;
import com.prayerroster.service.dto.UpdateWeeklyPrayerConfigurationRequest;
import com.prayerroster.service.dto.WeeklyPrayerConfigurationDTO;
import com.prayerroster.service.dto.WeeklyPrayerConfigurationDayDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the recurring weekly prayer pattern as a sequence of versions (see
 * docs/phase1-architecture.md section 4-5). Every update creates a new version and closes the
 * previous one, EXCEPT a second update on the same calendar day, which amends that day's version
 * in place instead of piling up multiple same-day versions with an otherwise-invalid
 * {@code effectiveTo < effectiveFrom} on the one being closed.
 */
@Service
@Transactional
public class WeeklyPrayerConfigurationService {

    private static final String ENTITY_NAME = "weeklyPrayerConfiguration";

    private final WeeklyPrayerConfigurationRepository repository;

    public WeeklyPrayerConfigurationService(WeeklyPrayerConfigurationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public WeeklyPrayerConfigurationDTO getCurrent() {
        return repository
            .findCurrent()
            .map(WeeklyPrayerConfigurationDTO::from)
            .orElseThrow(() -> new EntityNotFoundException("No weekly prayer configuration has been set yet"));
    }

    @Transactional(readOnly = true)
    public List<WeeklyPrayerConfigurationDTO> getHistory() {
        return repository.findAllWithDaysOrderByEffectiveFromDesc().stream().map(WeeklyPrayerConfigurationDTO::from).toList();
    }

    public WeeklyPrayerConfigurationDTO update(UpdateWeeklyPrayerConfigurationRequest request) {
        validateCoversEveryDayExactlyOnce(request.days());

        LocalDate today = LocalDate.now();
        Optional<WeeklyPrayerConfiguration> current = repository.findCurrent();

        WeeklyPrayerConfiguration target;
        if (current.isPresent() && current.get().getEffectiveFrom().equals(today)) {
            target = current.get();
            target.getDays().clear();
        } else {
            current.ifPresent(previous -> previous.setEffectiveTo(today.minusDays(1)));
            target = new WeeklyPrayerConfiguration();
            target.setEffectiveFrom(today);
        }

        for (WeeklyPrayerConfigurationDayDTO dayDto : request.days()) {
            WeeklyPrayerConfigurationDay day = new WeeklyPrayerConfigurationDay();
            day.setConfiguration(target);
            day.setDayOfWeek(dayDto.dayOfWeek());
            day.setRequiresPreacher(dayDto.requiresPreacher());
            target.getDays().add(day);
        }

        return WeeklyPrayerConfigurationDTO.from(repository.save(target));
    }

    /**
     * A 7-element {@code Set<DayOfWeek>} is necessarily every day exactly once - DayOfWeek has
     * exactly 7 possible values, so there is no way to have 7 distinct ones without covering all
     * of them. A separate "contains every day" check would be unreachable dead code.
     */
    private void validateCoversEveryDayExactlyOnce(List<WeeklyPrayerConfigurationDayDTO> days) {
        Set<DayOfWeek> provided = days.stream().map(WeeklyPrayerConfigurationDayDTO::dayOfWeek).collect(Collectors.toSet());
        if (provided.size() != 7) {
            throw new BadRequestAlertException(
                "Must provide exactly one entry for each day of the week, with no duplicates",
                ENTITY_NAME,
                "invalidDaySet"
            );
        }
    }
}

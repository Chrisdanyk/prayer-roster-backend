package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyPrayerConfigurationServiceTest {

    @Mock
    private WeeklyPrayerConfigurationRepository repository;

    private WeeklyPrayerConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyPrayerConfigurationService(repository);
    }

    private static List<WeeklyPrayerConfigurationDayDTO> fullWeek(DayOfWeek... preachingDays) {
        Set<DayOfWeek> preaching = Set.of(preachingDays);
        return Arrays.stream(DayOfWeek.values()).map(d -> new WeeklyPrayerConfigurationDayDTO(d, preaching.contains(d))).toList();
    }

    private static WeeklyPrayerConfiguration existingConfig(Long id, LocalDate effectiveFrom) {
        WeeklyPrayerConfiguration config = new WeeklyPrayerConfiguration();
        config.setId(id);
        config.setEffectiveFrom(effectiveFrom);
        Set<WeeklyPrayerConfigurationDay> days = new HashSet<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            WeeklyPrayerConfigurationDay day = new WeeklyPrayerConfigurationDay();
            day.setConfiguration(config);
            day.setDayOfWeek(d);
            day.setRequiresPreacher(d == DayOfWeek.SUNDAY);
            days.add(day);
        }
        config.setDays(days);
        return config;
    }

    @Test
    void getCurrent_returnsMappedDtoWhenPresent() {
        when(repository.findCurrent()).thenReturn(Optional.of(existingConfig(1L, LocalDate.now().minusDays(10))));

        WeeklyPrayerConfigurationDTO dto = service.getCurrent();

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.days()).hasSize(7);
    }

    @Test
    void getCurrent_throwsWhenNeverConfigured() {
        when(repository.findCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrent()).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getHistory_mapsAllVersions() {
        when(repository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(
            List.of(existingConfig(2L, LocalDate.now()), existingConfig(1L, LocalDate.now().minusMonths(1)))
        );

        List<WeeklyPrayerConfigurationDTO> history = service.getHistory();

        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(2L);
    }

    @Test
    void update_rejectsWhenADayIsMissing() {
        List<WeeklyPrayerConfigurationDayDTO> sixDays = fullWeek(DayOfWeek.SUNDAY)
            .stream()
            .filter(d -> d.dayOfWeek() != DayOfWeek.MONDAY)
            .toList();

        assertThatThrownBy(() -> service.update(new UpdateWeeklyPrayerConfigurationRequest(sixDays))).isInstanceOf(
            BadRequestAlertException.class
        );
    }

    @Test
    void update_rejectsWhenADayIsDuplicated() {
        List<WeeklyPrayerConfigurationDayDTO> days = new java.util.ArrayList<>(
            fullWeek(DayOfWeek.SUNDAY).stream().filter(d -> d.dayOfWeek() != DayOfWeek.TUESDAY).toList()
        );
        days.add(new WeeklyPrayerConfigurationDayDTO(DayOfWeek.MONDAY, false));

        assertThatThrownBy(() -> service.update(new UpdateWeeklyPrayerConfigurationRequest(days))).isInstanceOf(
            BadRequestAlertException.class
        );
    }

    @Test
    void update_createsFirstVersionWhenNeverConfigured() {
        when(repository.findCurrent()).thenReturn(Optional.empty());
        when(repository.save(any(WeeklyPrayerConfiguration.class))).thenAnswer(inv -> inv.getArgument(0));

        WeeklyPrayerConfigurationDTO result = service.update(new UpdateWeeklyPrayerConfigurationRequest(fullWeek(DayOfWeek.SUNDAY)));

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.now());
        assertThat(result.effectiveTo()).isNull();
        assertThat(result.days()).hasSize(7);
        assertThat(result.days()).filteredOn(WeeklyPrayerConfigurationDayDTO::requiresPreacher).extracting(
            WeeklyPrayerConfigurationDayDTO::dayOfWeek
        ).containsExactly(DayOfWeek.SUNDAY);
    }

    @Test
    void update_closesPreviousVersionAndCreatesNewOneOnADifferentDay() {
        WeeklyPrayerConfiguration previous = existingConfig(1L, LocalDate.now().minusDays(30));
        when(repository.findCurrent()).thenReturn(Optional.of(previous));
        when(repository.save(any(WeeklyPrayerConfiguration.class))).thenAnswer(inv -> inv.getArgument(0));

        WeeklyPrayerConfigurationDTO result = service.update(new UpdateWeeklyPrayerConfigurationRequest(fullWeek(DayOfWeek.WEDNESDAY)));

        assertThat(previous.getEffectiveTo()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(result.id()).isNull();
        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.now());

        ArgumentCaptor<WeeklyPrayerConfiguration> captor = ArgumentCaptor.forClass(WeeklyPrayerConfiguration.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isNotSameAs(previous);
    }

    @Test
    void update_amendsInPlaceWhenAlreadyChangedToday() {
        WeeklyPrayerConfiguration current = existingConfig(1L, LocalDate.now());
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.save(any(WeeklyPrayerConfiguration.class))).thenAnswer(inv -> inv.getArgument(0));

        WeeklyPrayerConfigurationDTO result = service.update(new UpdateWeeklyPrayerConfigurationRequest(fullWeek(DayOfWeek.FRIDAY)));

        assertThat(result.id()).isEqualTo(1L);
        assertThat(current.getEffectiveTo()).isNull();

        ArgumentCaptor<WeeklyPrayerConfiguration> captor = ArgumentCaptor.forClass(WeeklyPrayerConfiguration.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(current);
        assertThat(result.days()).filteredOn(WeeklyPrayerConfigurationDayDTO::requiresPreacher).extracting(
            WeeklyPrayerConfigurationDayDTO::dayOfWeek
        ).containsExactly(DayOfWeek.FRIDAY);
    }
}

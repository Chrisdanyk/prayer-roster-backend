package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.repository.ReminderConfigurationRepository;
import com.prayerroster.service.dto.ReminderConfigurationDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderConfigurationServiceTest {

    @Mock
    private ReminderConfigurationRepository reminderConfigurationRepository;

    private ReminderConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new ReminderConfigurationService(reminderConfigurationRepository);
    }

    private static ReminderConfiguration configuration(Long id, int daysBefore, boolean active) {
        ReminderConfiguration configuration = new ReminderConfiguration();
        configuration.setId(id);
        configuration.setDaysBefore(daysBefore);
        configuration.setActive(active);
        return configuration;
    }

    @Test
    void findAll_mapsEveryConfigurationToDto() {
        when(reminderConfigurationRepository.findAll()).thenReturn(List.of(configuration(1L, 7, true), configuration(2L, 1, true)));

        List<ReminderConfigurationDTO> result = service.findAll();

        assertThat(result).extracting(ReminderConfigurationDTO::daysBefore).containsExactly(7, 1);
    }

    @Test
    void create_savesNewOffset() {
        when(reminderConfigurationRepository.existsByDaysBefore(3)).thenReturn(false);
        when(reminderConfigurationRepository.save(any(ReminderConfiguration.class))).thenAnswer(inv -> {
            ReminderConfiguration c = inv.getArgument(0);
            c.setId(9L);
            return c;
        });

        ReminderConfigurationDTO result = service.create(3);

        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.daysBefore()).isEqualTo(3);
        assertThat(result.active()).isTrue();
    }

    @Test
    void create_rejectsDuplicateOffset() {
        when(reminderConfigurationRepository.existsByDaysBefore(7)).thenReturn(true);

        assertThatThrownBy(() -> service.create(7)).isInstanceOf(BadRequestAlertException.class);
        verify(reminderConfigurationRepository, never()).save(any());
    }

    @Test
    void updateActive_togglesActiveFlag() {
        ReminderConfiguration existing = configuration(1L, 7, true);
        when(reminderConfigurationRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(reminderConfigurationRepository.save(existing)).thenReturn(existing);

        ReminderConfigurationDTO result = service.updateActive(1L, false);

        assertThat(result.active()).isFalse();
    }

    @Test
    void updateActive_throwsWhenNotFound() {
        when(reminderConfigurationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateActive(99L, false)).isInstanceOf(EntityNotFoundException.class);
    }
}

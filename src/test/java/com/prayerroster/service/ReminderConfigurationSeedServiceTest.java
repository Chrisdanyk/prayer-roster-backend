package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.repository.ReminderConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class ReminderConfigurationSeedServiceTest {

    @Mock
    private ReminderConfigurationRepository reminderConfigurationRepository;

    private ReminderConfigurationSeedService service;

    @BeforeEach
    void setUp() {
        service = new ReminderConfigurationSeedService(reminderConfigurationRepository);
    }

    @Test
    void run_seedsDefaultOffsetsWhenNoneExist() {
        when(reminderConfigurationRepository.count()).thenReturn(0L);
        when(reminderConfigurationRepository.save(any(ReminderConfiguration.class))).thenAnswer(inv -> inv.getArgument(0));

        service.run(new DefaultApplicationArguments());

        ArgumentCaptor<ReminderConfiguration> captor = ArgumentCaptor.forClass(ReminderConfiguration.class);
        verify(reminderConfigurationRepository, times(2)).save(captor.capture());
        assertThatDaysBeforeAre(captor.getAllValues(), 7, 1);
    }

    @Test
    void run_doesNothingWhenAlreadySeeded() {
        when(reminderConfigurationRepository.count()).thenReturn(2L);

        service.run(new DefaultApplicationArguments());

        verify(reminderConfigurationRepository, never()).save(any());
    }

    private static void assertThatDaysBeforeAre(java.util.List<ReminderConfiguration> configurations, int... expected) {
        int[] actual = configurations.stream().mapToInt(ReminderConfiguration::getDaysBefore).toArray();
        assertThat(actual).containsExactly(expected);
    }
}

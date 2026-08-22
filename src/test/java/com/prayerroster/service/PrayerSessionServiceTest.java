package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerSession;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrayerSessionServiceTest {

    @Mock
    private PrayerSessionRepository prayerSessionRepository;

    private PrayerSessionService service;

    @BeforeEach
    void setUp() {
        service = new PrayerSessionService(prayerSessionRepository);
    }

    private static PrayerSession session(Long id, LocalDate date) {
        PrayerSession s = new PrayerSession();
        s.setId(id);
        s.setDate(date);
        s.setDayOfWeek(date.getDayOfWeek());
        s.setRequiresPreacher(false);
        return s;
    }

    @Test
    void findByDateRange_rejectsWhenFromAfterTo() {
        LocalDate from = LocalDate.of(2026, 9, 10);
        LocalDate to = LocalDate.of(2026, 9, 1);

        assertThatThrownBy(() -> service.findByDateRange(from, to)).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void findByDateRange_mapsResults() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 7);
        when(prayerSessionRepository.findByDateBetweenWithAssignments(from, to)).thenReturn(List.of(session(1L, from)));

        List<com.prayerroster.service.dto.PrayerSessionDTO> result = service.findByDateRange(from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void findOne_returnsDtoWhenFound() {
        when(prayerSessionRepository.findByIdWithAssignments(1L)).thenReturn(Optional.of(session(1L, LocalDate.of(2026, 9, 1))));

        assertThat(service.findOne(1L).id()).isEqualTo(1L);
    }

    @Test
    void findOne_throwsWhenNotFound() {
        when(prayerSessionRepository.findByIdWithAssignments(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOne(99L)).isInstanceOf(EntityNotFoundException.class);
    }
}

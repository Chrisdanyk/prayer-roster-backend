package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.RosterGenerationDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RosterServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationRepository generationRepository;

    private RosterService service;

    @BeforeEach
    void setUp() {
        service = new RosterService(rosterRepository, generationRepository);
    }

    private static Roster roster(Long id) {
        Roster r = new Roster();
        r.setId(id);
        r.setPeriodFrom(LocalDate.of(2026, 9, 1));
        r.setPeriodTo(LocalDate.of(2026, 9, 30));
        r.setStatus(RosterStatus.DRAFT);
        return r;
    }

    @Test
    void findAll_mapsPageToDtos() {
        var pageable = PageRequest.of(0, 20);
        when(rosterRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(roster(1L))));

        var page = service.findAll(pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(1L);
    }

    @Test
    void findOne_returnsDtoWhenFound() {
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster(1L)));

        assertThat(service.findOne(1L).status()).isEqualTo(RosterStatus.DRAFT);
    }

    @Test
    void findOne_throwsWhenNotFound() {
        when(rosterRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOne(99L)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void findGenerations_returnsTheAuditTrailMostRecentFirst() {
        when(rosterRepository.existsById(1L)).thenReturn(true);
        RosterGeneration generation = new RosterGeneration();
        generation.setId(7L);
        generation.setStatus(RosterGenerationStatus.COMPLETED);
        generation.setHardScore(0);
        generation.setSoftScore(-12);
        generation.setSolverDurationMs(1800L);
        when(generationRepository.findByRosterIdMostRecentFirst(1L)).thenReturn(List.of(generation));

        List<RosterGenerationDTO> result = service.findGenerations(1L);

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.hardScore()).isZero();
            assertThat(dto.softScore()).isEqualTo(-12);
            assertThat(dto.solverDurationMs()).isEqualTo(1800L);
        });
    }

    @Test
    void findGenerations_rejectsAnUnknownRoster() {
        when(rosterRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.findGenerations(99L)).isInstanceOf(EntityNotFoundException.class);
    }
}

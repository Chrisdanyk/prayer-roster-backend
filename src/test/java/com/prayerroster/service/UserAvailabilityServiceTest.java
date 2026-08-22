package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import com.prayerroster.domain.UserAvailabilityStatus;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class UserAvailabilityServiceTest {

    private static final String USER_ID = "sub-1";

    @Mock
    private UserAvailabilityRepository availabilityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new UserAvailabilityService(availabilityRepository, userRepository, eventPublisher);
        lenient().when(availabilityRepository.findOverlapping(eq(USER_ID), any(), any(), any())).thenReturn(List.of());
    }

    private UserAvailability existing(LocalDate start, LocalDate end) {
        User user = new User();
        user.setId(USER_ID);
        UserAvailability availability = new UserAvailability();
        availability.setId(42L);
        availability.setUser(user);
        availability.setStartDate(start);
        availability.setEndDate(end);
        availability.setStatus(UserAvailabilityStatus.ACTIVE);
        return availability;
    }

    @Test
    void findOwn_mapsRepositoryResultsToDtos() {
        UserAvailability record = existing(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
        when(availabilityRepository.findByUserIdOrderByStartDateDesc(USER_ID)).thenReturn(List.of(record));

        List<UserAvailabilityDTO> result = service.findOwn(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(42L);
    }

    @Test
    void create_savesNewActiveAvailability() {
        User user = new User();
        user.setId(USER_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(availabilityRepository.save(any(UserAvailability.class))).thenAnswer(inv -> {
            UserAvailability a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(5);

        UserAvailabilityDTO result = service.create(USER_ID, new AvailabilityRequest(start, end, "Voyage"));

        assertThat(result.startDate()).isEqualTo(start);
        assertThat(result.endDate()).isEqualTo(end);
        assertThat(result.status()).isEqualTo(UserAvailabilityStatus.ACTIVE);

        ArgumentCaptor<UserAvailabilityChangedEvent> eventCaptor = ArgumentCaptor.forClass(UserAvailabilityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new UserAvailabilityChangedEvent(USER_ID, start, end));
    }

    @Test
    void create_rejectsWhenStartDateAfterEndDate() {
        LocalDate start = LocalDate.now().plusDays(5);
        LocalDate end = LocalDate.now().plusDays(1);

        assertThatThrownBy(() -> service.create(USER_ID, new AvailabilityRequest(start, end, null))).isInstanceOf(
            BadRequestAlertException.class
        );
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    void create_rejectsOverlappingPeriod() {
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(5);
        when(availabilityRepository.findOverlapping(USER_ID, start, end, null)).thenReturn(List.of(existing(start, end)));

        assertThatThrownBy(() -> service.create(USER_ID, new AvailabilityRequest(start, end, null))).isInstanceOf(
            BadRequestAlertException.class
        );
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    void update_modifiesExistingRecord() {
        UserAvailability record = existing(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
        when(availabilityRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(record));
        when(availabilityRepository.save(record)).thenReturn(record);
        LocalDate newStart = LocalDate.now().plusDays(2);
        LocalDate newEnd = LocalDate.now().plusDays(6);

        UserAvailabilityDTO result = service.update(USER_ID, 42L, new AvailabilityRequest(newStart, newEnd, "Updated"));

        assertThat(result.startDate()).isEqualTo(newStart);
        assertThat(result.endDate()).isEqualTo(newEnd);
        assertThat(result.reason()).isEqualTo("Updated");
        verify(eventPublisher).publishEvent(new UserAvailabilityChangedEvent(USER_ID, newStart, newEnd));
    }

    @Test
    void update_throwsWhenNotOwnedOrMissing() {
        when(availabilityRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(2);

        assertThatThrownBy(() -> service.update(USER_ID, 99L, new AvailabilityRequest(start, end, null))).isInstanceOf(
            EntityNotFoundException.class
        );
    }

    @Test
    void update_rejectsWhenRecordIsFullyInThePast() {
        UserAvailability pastRecord = existing(LocalDate.now().minusDays(10), LocalDate.now().minusDays(5));
        when(availabilityRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(pastRecord));
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(2);

        assertThatThrownBy(() -> service.update(USER_ID, 42L, new AvailabilityRequest(start, end, null))).isInstanceOf(
            BadRequestAlertException.class
        );
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    void cancel_setsStatusToCancelled() {
        UserAvailability record = existing(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
        when(availabilityRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(record));
        when(availabilityRepository.save(record)).thenReturn(record);

        service.cancel(USER_ID, 42L);

        assertThat(record.getStatus()).isEqualTo(UserAvailabilityStatus.CANCELLED);
    }

    @Test
    void cancel_rejectsWhenRecordIsFullyInThePast() {
        UserAvailability pastRecord = existing(LocalDate.now().minusDays(10), LocalDate.now().minusDays(5));
        when(availabilityRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(pastRecord));

        assertThatThrownBy(() -> service.cancel(USER_ID, 42L)).isInstanceOf(BadRequestAlertException.class);
        verify(availabilityRepository, never()).save(any());
    }
}

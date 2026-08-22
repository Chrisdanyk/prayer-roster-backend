package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.NotificationPreference;
import com.prayerroster.domain.User;
import com.prayerroster.repository.NotificationPreferenceRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.service.dto.NotificationPreferenceDTO;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Mock
    private UserRepository userRepository;

    private NotificationPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new NotificationPreferenceService(notificationPreferenceRepository, userRepository);
    }

    @Test
    void findOwn_returnsDefaultEnabledWhenNoRowExistsYet() {
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.empty());

        NotificationPreferenceDTO result = service.findOwn("u1");

        assertThat(result.emailEnabled()).isTrue();
    }

    @Test
    void findOwn_returnsExistingPreference() {
        NotificationPreference existing = new NotificationPreference();
        existing.setEmailEnabled(false);
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.of(existing));

        NotificationPreferenceDTO result = service.findOwn("u1");

        assertThat(result.emailEnabled()).isFalse();
    }

    @Test
    void update_createsRowWhenNoneExists() {
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.empty());
        User user = new User();
        user.setId("u1");
        when(userRepository.getReferenceById("u1")).thenReturn(user);
        when(notificationPreferenceRepository.save(any(NotificationPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationPreferenceDTO result = service.update("u1", false);

        assertThat(result.emailEnabled()).isFalse();
        verify(notificationPreferenceRepository).save(any(NotificationPreference.class));
    }

    @Test
    void update_modifiesExistingRow() {
        NotificationPreference existing = new NotificationPreference();
        existing.setEmailEnabled(true);
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.of(existing));
        when(notificationPreferenceRepository.save(existing)).thenReturn(existing);

        NotificationPreferenceDTO result = service.update("u1", false);

        assertThat(result.emailEnabled()).isFalse();
        assertThat(existing.isEmailEnabled()).isFalse();
    }
}

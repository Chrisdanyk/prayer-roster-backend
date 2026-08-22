package com.prayerroster.service;

import com.prayerroster.domain.NotificationPreference;
import com.prayerroster.repository.NotificationPreferenceRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.service.dto.NotificationPreferenceDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service notification channel preference - lazily creates the default (email enabled) row on
 * first read/write rather than needing a seed step at user-provisioning time.
 */
@Service
@Transactional
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final UserRepository userRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository notificationPreferenceRepository, UserRepository userRepository) {
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceDTO findOwn(String userId) {
        return notificationPreferenceRepository
            .findByUserId(userId)
            .map(NotificationPreferenceDTO::from)
            .orElse(new NotificationPreferenceDTO(true));
    }

    public NotificationPreferenceDTO update(String userId, boolean emailEnabled) {
        NotificationPreference preference = notificationPreferenceRepository.findByUserId(userId).orElseGet(() -> {
            NotificationPreference created = new NotificationPreference();
            created.setUser(userRepository.getReferenceById(userId));
            return created;
        });
        preference.setEmailEnabled(emailEnabled);
        return NotificationPreferenceDTO.from(notificationPreferenceRepository.save(preference));
    }
}

package com.prayerroster.web.rest;

import com.prayerroster.security.SecurityUtils;
import com.prayerroster.service.NotificationPreferenceService;
import com.prayerroster.service.dto.NotificationPreferenceDTO;
import com.prayerroster.service.dto.UpdateNotificationPreferenceRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Self-service notification channel preference - authenticated-only, own data only. */
@RestController
@RequestMapping("/api/me/notification-preference")
public class MeNotificationPreferenceResource {

    private final NotificationPreferenceService notificationPreferenceService;

    public MeNotificationPreferenceResource(NotificationPreferenceService notificationPreferenceService) {
        this.notificationPreferenceService = notificationPreferenceService;
    }

    @GetMapping
    public NotificationPreferenceDTO getOwnPreference() {
        return notificationPreferenceService.findOwn(currentUserId());
    }

    @PutMapping
    public NotificationPreferenceDTO updateOwnPreference(@Valid @RequestBody UpdateNotificationPreferenceRequest request) {
        return notificationPreferenceService.update(currentUserId(), request.emailEnabled());
    }

    private String currentUserId() {
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}

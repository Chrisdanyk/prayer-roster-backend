package com.prayerroster.web.rest;

import com.prayerroster.security.SecurityUtils;
import com.prayerroster.service.NotificationService;
import com.prayerroster.service.dto.NotificationDTO;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/**
 * Self-service notification inbox. Deliberately not permission-gated beyond authentication - every
 * authenticated user reads only their own notifications, scoped by the JWT's sub claim.
 */
@RestController
@RequestMapping("/api/me/notifications")
public class MeNotificationResource {

    private final NotificationService notificationService;

    public MeNotificationResource(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationDTO>> getOwnNotifications(@PageableDefault(size = 20) Pageable pageable) {
        Page<NotificationDTO> page = notificationService.findOwn(currentUserId(), pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    @PutMapping("/{id}/read")
    public NotificationDTO markRead(@PathVariable Long id) {
        return notificationService.markRead(currentUserId(), id);
    }

    private String currentUserId() {
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}

package com.prayerroster.web.rest;

import com.prayerroster.service.ReminderConfigurationService;
import com.prayerroster.service.dto.CreateReminderConfigurationRequest;
import com.prayerroster.service.dto.ReminderConfigurationDTO;
import com.prayerroster.service.dto.UpdateReminderConfigurationRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin management of the global reminder offsets. */
@RestController
@RequestMapping("/api/reminder-config")
public class ReminderConfigurationResource {

    private final ReminderConfigurationService reminderConfigurationService;

    public ReminderConfigurationResource(ReminderConfigurationService reminderConfigurationService) {
        this.reminderConfigurationService = reminderConfigurationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_REMINDER_CONFIG_VIEW')")
    public List<ReminderConfigurationDTO> getReminderConfigurations() {
        return reminderConfigurationService.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_REMINDER_CONFIG_UPDATE')")
    public ResponseEntity<ReminderConfigurationDTO> createReminderConfiguration(@Valid @RequestBody CreateReminderConfigurationRequest request) {
        ReminderConfigurationDTO created = reminderConfigurationService.create(request.daysBefore());
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_REMINDER_CONFIG_UPDATE')")
    public ReminderConfigurationDTO updateReminderConfiguration(
        @PathVariable Long id,
        @Valid @RequestBody UpdateReminderConfigurationRequest request
    ) {
        return reminderConfigurationService.updateActive(id, request.active());
    }
}

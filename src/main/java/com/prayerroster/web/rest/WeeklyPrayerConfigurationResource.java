package com.prayerroster.web.rest;

import com.prayerroster.service.WeeklyPrayerConfigurationService;
import com.prayerroster.service.dto.UpdateWeeklyPrayerConfigurationRequest;
import com.prayerroster.service.dto.WeeklyPrayerConfigurationDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Manages the recurring weekly prayer pattern. Every mutation is permission-gated, never by role name. */
@RestController
@RequestMapping("/api/prayer-config/weekly")
public class WeeklyPrayerConfigurationResource {

    private final WeeklyPrayerConfigurationService configurationService;

    public WeeklyPrayerConfigurationResource(WeeklyPrayerConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PRAYER_CONFIG_VIEW')")
    public WeeklyPrayerConfigurationDTO getCurrent() {
        return configurationService.getCurrent();
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('PERM_PRAYER_CONFIG_VIEW')")
    public List<WeeklyPrayerConfigurationDTO> getHistory() {
        return configurationService.getHistory();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERM_PRAYER_CONFIG_UPDATE')")
    public WeeklyPrayerConfigurationDTO update(@Valid @RequestBody UpdateWeeklyPrayerConfigurationRequest request) {
        return configurationService.update(request);
    }
}

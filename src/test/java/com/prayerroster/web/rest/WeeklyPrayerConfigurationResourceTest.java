package com.prayerroster.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.service.WeeklyPrayerConfigurationService;
import com.prayerroster.service.dto.UpdateWeeklyPrayerConfigurationRequest;
import com.prayerroster.service.dto.WeeklyPrayerConfigurationDTO;
import com.prayerroster.service.dto.WeeklyPrayerConfigurationDayDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WeeklyPrayerConfigurationResourceTest {

    @Mock
    private WeeklyPrayerConfigurationService configurationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static List<WeeklyPrayerConfigurationDayDTO> fullWeek() {
        Set<DayOfWeek> preaching = Set.of(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY);
        return Arrays.stream(DayOfWeek.values()).map(d -> new WeeklyPrayerConfigurationDayDTO(d, preaching.contains(d))).toList();
    }

    private static WeeklyPrayerConfigurationDTO sampleConfig() {
        return new WeeklyPrayerConfigurationDTO(1L, LocalDate.now(), null, fullWeek());
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new WeeklyPrayerConfigurationResource(configurationService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getCurrent_returnsConfiguration() throws Exception {
        when(configurationService.getCurrent()).thenReturn(sampleConfig());

        mockMvc.perform(get("/api/prayer-config/weekly")).andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(7));
    }

    @Test
    void getCurrent_returns404WhenNeverConfigured() throws Exception {
        when(configurationService.getCurrent()).thenThrow(new EntityNotFoundException("not configured"));

        mockMvc.perform(get("/api/prayer-config/weekly")).andExpect(status().isNotFound());
    }

    @Test
    void getHistory_returnsAllVersions() throws Exception {
        when(configurationService.getHistory()).thenReturn(List.of(sampleConfig()));

        mockMvc
            .perform(get("/api/prayer-config/weekly/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void update_returnsUpdatedConfiguration() throws Exception {
        when(configurationService.update(any())).thenReturn(sampleConfig());
        var request = new UpdateWeeklyPrayerConfigurationRequest(fullWeek());

        mockMvc
            .perform(put("/api/prayer-config/weekly").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.days.length()").value(7));
    }

    @Test
    void update_returns400WhenNotExactlySevenDays() throws Exception {
        var request = new UpdateWeeklyPrayerConfigurationRequest(fullWeek().subList(0, 6));

        mockMvc
            .perform(put("/api/prayer-config/weekly").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }
}

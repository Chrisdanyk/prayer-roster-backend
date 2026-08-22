package com.prayerroster.web.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.service.PrayerSessionService;
import com.prayerroster.service.dto.PrayerSessionDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PrayerSessionResourceTest {

    @Mock
    private PrayerSessionService prayerSessionService;

    private MockMvc mockMvc;

    private static PrayerSessionDTO sampleSession() {
        return new PrayerSessionDTO(1L, LocalDate.of(2026, 9, 6), DayOfWeek.SUNDAY, true, false, List.of());
    }

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new PrayerSessionResource(prayerSessionService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getSessions_returnsSessionsInRange() throws Exception {
        when(prayerSessionService.findByDateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(
            List.of(sampleSession())
        );

        mockMvc
            .perform(get("/api/prayer-sessions").param("from", "2026-09-01").param("to", "2026-09-30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void getSession_returnsSession() throws Exception {
        when(prayerSessionService.findOne(1L)).thenReturn(sampleSession());

        mockMvc.perform(get("/api/prayer-sessions/1")).andExpect(status().isOk()).andExpect(jsonPath("$.requiresPreacher").value(true));
    }

    @Test
    void getSession_returns404WhenNotFound() throws Exception {
        when(prayerSessionService.findOne(99L)).thenThrow(new EntityNotFoundException("not found"));

        mockMvc.perform(get("/api/prayer-sessions/99")).andExpect(status().isNotFound());
    }
}

package com.prayerroster.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.service.RosterGenerationService;
import com.prayerroster.service.RosterService;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RosterResourceTest {

    @Mock
    private RosterGenerationService rosterGenerationService;

    @Mock
    private RosterService rosterService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static RosterDTO sampleRoster() {
        return new RosterDTO(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), RosterStatus.DRAFT, null);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new RosterResource(rosterGenerationService, rosterService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    @Test
    void generate_returns201WithCreatedRoster() throws Exception {
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        when(rosterGenerationService.generate(any())).thenReturn(sampleRoster());

        mockMvc
            .perform(post("/api/rosters/generate").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void generate_returns400OnOverlap() throws Exception {
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        when(rosterGenerationService.generate(any())).thenThrow(new BadRequestAlertException("overlap", "roster", "periodOverlap"));

        mockMvc
            .perform(post("/api/rosters/generate").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getRosters_returnsPageContentWithPaginationHeader() throws Exception {
        when(rosterService.findAll(any())).thenReturn(new PageImpl<>(java.util.List.of(sampleRoster()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/rosters")).andExpect(status().isOk()).andExpect(header().exists("X-Total-Count"));
    }

    @Test
    void getRoster_returnsRoster() throws Exception {
        when(rosterService.findOne(1L)).thenReturn(sampleRoster());

        mockMvc.perform(get("/api/rosters/1")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getRoster_returns404WhenNotFound() throws Exception {
        when(rosterService.findOne(99L)).thenThrow(new EntityNotFoundException("not found"));

        mockMvc.perform(get("/api/rosters/99")).andExpect(status().isNotFound());
    }
}

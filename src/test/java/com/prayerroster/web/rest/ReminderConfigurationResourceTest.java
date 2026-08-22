package com.prayerroster.web.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.ReminderConfigurationService;
import com.prayerroster.service.dto.CreateReminderConfigurationRequest;
import com.prayerroster.service.dto.ReminderConfigurationDTO;
import com.prayerroster.service.dto.UpdateReminderConfigurationRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.util.List;
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
class ReminderConfigurationResourceTest {

    @Mock
    private ReminderConfigurationService reminderConfigurationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new ReminderConfigurationResource(reminderConfigurationService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getReminderConfigurations_returnsList() throws Exception {
        when(reminderConfigurationService.findAll()).thenReturn(List.of(new ReminderConfigurationDTO(1L, 7, true), new ReminderConfigurationDTO(2L, 1, true)));

        mockMvc.perform(get("/api/reminder-config")).andExpect(status().isOk()).andExpect(jsonPath("$[0].daysBefore").value(7));
    }

    @Test
    void createReminderConfiguration_returns201() throws Exception {
        CreateReminderConfigurationRequest request = new CreateReminderConfigurationRequest(3);
        when(reminderConfigurationService.create(3)).thenReturn(new ReminderConfigurationDTO(9L, 3, true));

        mockMvc
            .perform(post("/api/reminder-config").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.daysBefore").value(3));
    }

    @Test
    void createReminderConfiguration_returns400OnDuplicate() throws Exception {
        CreateReminderConfigurationRequest request = new CreateReminderConfigurationRequest(7);
        when(reminderConfigurationService.create(7)).thenThrow(new BadRequestAlertException("duplicate", "reminderConfiguration", "duplicateOffset"));

        mockMvc
            .perform(post("/api/reminder-config").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateReminderConfiguration_returnsUpdated() throws Exception {
        UpdateReminderConfigurationRequest request = new UpdateReminderConfigurationRequest(false);
        when(reminderConfigurationService.updateActive(1L, false)).thenReturn(new ReminderConfigurationDTO(1L, 7, false));

        mockMvc
            .perform(put("/api/reminder-config/1").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void updateReminderConfiguration_returns404WhenNotFound() throws Exception {
        UpdateReminderConfigurationRequest request = new UpdateReminderConfigurationRequest(false);
        when(reminderConfigurationService.updateActive(99L, false)).thenThrow(new EntityNotFoundException("not found"));

        mockMvc
            .perform(put("/api/reminder-config/99").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());
    }
}

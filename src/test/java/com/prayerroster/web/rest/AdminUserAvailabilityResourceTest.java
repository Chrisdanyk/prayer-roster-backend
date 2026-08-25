package com.prayerroster.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.domain.UserAvailabilityStatus;
import com.prayerroster.service.UserAvailabilityService;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.LocalDate;
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
class AdminUserAvailabilityResourceTest {

    @Mock
    private UserAvailabilityService userAvailabilityService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserAvailabilityResource(userAvailabilityService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    private static UserAvailabilityDTO sampleAvailability() {
        return new UserAvailabilityDTO(1L, LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "Voyage", UserAvailabilityStatus.ACTIVE);
    }

    @Test
    void getForUser_returnsTheMembersList() throws Exception {
        when(userAvailabilityService.findOwn("sub-9")).thenReturn(List.of(sampleAvailability()));

        mockMvc
            .perform(get("/api/users/{userId}/availability", "sub-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1));

        verify(userAvailabilityService).findOwn("sub-9");
    }

    @Test
    void createForUser_recordsAgainstThePathUserNotTheCaller() throws Exception {
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18"), "Voyage");
        when(userAvailabilityService.create("sub-9", request)).thenReturn(
            new UserAvailabilityDTO(1L, request.startDate(), request.endDate(), "Voyage", UserAvailabilityStatus.ACTIVE)
        );

        mockMvc
            .perform(
                post("/api/users/{userId}/availability", "sub-9")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isCreated());

        verify(userAvailabilityService).create("sub-9", request);
    }

    @Test
    void createForUser_returns400WhenValidationFails() throws Exception {
        when(userAvailabilityService.create(eq("sub-9"), any())).thenThrow(
            new com.prayerroster.web.rest.errors.BadRequestAlertException("Overlaps", "userAvailability", "overlappingPeriod")
        );
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18"), null);

        mockMvc
            .perform(
                post("/api/users/{userId}/availability", "sub-9")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void cancelForUser_returns204() throws Exception {
        mockMvc.perform(delete("/api/users/{userId}/availability/{availabilityId}", "sub-9", 1L)).andExpect(status().isNoContent());

        verify(userAvailabilityService).cancel("sub-9", 1L);
    }

    @Test
    void cancelForUser_returns404WhenNotOwnedByThatUser() throws Exception {
        org.mockito.Mockito.doThrow(new EntityNotFoundException("not found")).when(userAvailabilityService).cancel("sub-9", 99L);

        mockMvc.perform(delete("/api/users/{userId}/availability/{availabilityId}", "sub-9", 99L)).andExpect(status().isNotFound());
    }
}

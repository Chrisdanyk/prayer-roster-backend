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
import com.prayerroster.web.rest.errors.BadRequestAlertException;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;

@ExtendWith(MockitoExtension.class)
class MeAvailabilityResourceTest {

    private static final String USER_ID = "sub-1";

    @Mock
    private UserAvailabilityService availabilityService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new MeAvailabilityResource(availabilityService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(googleJwt(USER_ID, "jean@example.com")));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static UserAvailabilityDTO sampleAvailability() {
        return new UserAvailabilityDTO(1L, LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "Voyage", UserAvailabilityStatus.ACTIVE);
    }

    @Test
    void getOwnAvailability_returnsOwnRecordsOnly() throws Exception {
        when(availabilityService.findOwn(USER_ID)).thenReturn(List.of(sampleAvailability()));

        mockMvc.perform(get("/api/me/availability")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(1));

        verify(availabilityService).findOwn(USER_ID);
    }

    @Test
    void createAvailability_returns201() throws Exception {
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "Voyage");
        when(availabilityService.create(eq(USER_ID), any())).thenReturn(sampleAvailability());

        mockMvc
            .perform(post("/api/me/availability").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reason").value("Voyage"));
    }

    @Test
    void createAvailability_returns400WhenOverlapping() throws Exception {
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), null);
        when(availabilityService.create(eq(USER_ID), any())).thenThrow(
            new BadRequestAlertException("Overlaps", "userAvailability", "overlappingPeriod")
        );

        mockMvc
            .perform(post("/api/me/availability").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateAvailability_returnsUpdated() throws Exception {
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.now().plusDays(2), LocalDate.now().plusDays(4), "Updated");
        when(availabilityService.update(eq(USER_ID), eq(1L), any())).thenReturn(sampleAvailability());

        mockMvc
            .perform(
                put("/api/me/availability/1").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isOk());
    }

    @Test
    void updateAvailability_returns404WhenNotOwned() throws Exception {
        AvailabilityRequest request = new AvailabilityRequest(LocalDate.now().plusDays(2), LocalDate.now().plusDays(4), null);
        when(availabilityService.update(eq(USER_ID), eq(99L), any())).thenThrow(new EntityNotFoundException("not found"));

        mockMvc
            .perform(
                put("/api/me/availability/99").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isNotFound());
    }

    @Test
    void cancelAvailability_returns204() throws Exception {
        mockMvc.perform(delete("/api/me/availability/1")).andExpect(status().isNoContent());

        verify(availabilityService).cancel(USER_ID, 1L);
    }

    @Test
    void getOwnAvailability_returns500WhenSomehowUnauthenticated() throws Exception {
        // The security filter chain rejects unauthenticated /api/** requests before this
        // controller is ever reached in production; this proves the fallback is sound anyway.
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/me/availability")).andExpect(status().is5xxServerError());
    }
}

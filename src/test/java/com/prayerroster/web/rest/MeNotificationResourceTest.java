package com.prayerroster.web.rest;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.domain.EmailStatus;
import com.prayerroster.domain.NotificationType;
import com.prayerroster.service.NotificationService;
import com.prayerroster.service.dto.NotificationDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MeNotificationResourceTest {

    private static final String USER_ID = "sub-1";

    @Mock
    private NotificationService notificationService;

    private MockMvc mockMvc;

    private static NotificationDTO sampleNotification() {
        return new NotificationDTO(
            1L,
            NotificationType.ASSIGNMENT_PUBLISHED,
            "Nouvelle affectation",
            "Vous avez été affecté(e)...",
            false,
            null,
            EmailStatus.SENT,
            Instant.parse("2026-09-01T08:00:00Z")
        );
    }

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new MeNotificationResource(notificationService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(googleJwt(USER_ID, "jean@example.com")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOwnNotifications_returnsPageContentWithPaginationHeader() throws Exception {
        when(notificationService.findOwn(eq(USER_ID), any())).thenReturn(new PageImpl<>(List.of(sampleNotification()), PageRequest.of(0, 20), 1));

        mockMvc
            .perform(get("/api/me/notifications"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"))
            .andExpect(jsonPath("$[0].subject").value("Nouvelle affectation"));
    }

    @Test
    void markRead_returnsUpdatedNotification() throws Exception {
        NotificationDTO read = new NotificationDTO(
            1L,
            NotificationType.ASSIGNMENT_PUBLISHED,
            "Nouvelle affectation",
            "Vous avez été affecté(e)...",
            true,
            Instant.parse("2026-09-02T08:00:00Z"),
            EmailStatus.SENT,
            Instant.parse("2026-09-01T08:00:00Z")
        );
        when(notificationService.markRead(USER_ID, 1L)).thenReturn(read);

        mockMvc.perform(put("/api/me/notifications/1/read")).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));

        verify(notificationService).markRead(USER_ID, 1L);
    }

    @Test
    void markRead_returns404WhenNotOwnedOrMissing() throws Exception {
        when(notificationService.markRead(USER_ID, 99L)).thenThrow(new EntityNotFoundException("not found"));

        mockMvc.perform(put("/api/me/notifications/99/read")).andExpect(status().isNotFound());
    }

    @Test
    void getOwnNotifications_returns500WhenSomehowUnauthenticated() throws Exception {
        // The security filter chain rejects unauthenticated /api/** requests before this
        // controller is ever reached in production; this proves the fallback is sound anyway.
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/me/notifications")).andExpect(status().is5xxServerError());
    }
}

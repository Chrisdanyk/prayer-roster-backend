package com.prayerroster.web.rest;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.User;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.service.PrayerAssignmentCalendarPdfService;
import com.prayerroster.service.PrayerAssignmentService;
import com.prayerroster.service.dto.UpcomingAssignmentDTO;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MePrayerAssignmentResourceTest {

    private static final String USER_ID = "sub-1";

    @Mock
    private PrayerAssignmentService prayerAssignmentService;

    @Mock
    private PrayerAssignmentCalendarPdfService prayerAssignmentCalendarPdfService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new MePrayerAssignmentResource(prayerAssignmentService, prayerAssignmentCalendarPdfService, userRepository)
        )
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper), new ByteArrayHttpMessageConverter())
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(googleJwt(USER_ID, "jean@example.com")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOwnUpcomingAssignments_returnsOwnAssignments() throws Exception {
        when(prayerAssignmentService.findOwnUpcoming(USER_ID)).thenReturn(
            List.of(new UpcomingAssignmentDTO(1L, LocalDate.of(2026, 9, 6), PrayerAssignmentRole.MODERATOR))
        );

        mockMvc
            .perform(get("/api/me/prayer-assignments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].role").value("MODERATOR"));
    }

    @Test
    void getOwnUpcomingAssignmentsPdf_usesOwnLocale() throws Exception {
        User user = new User();
        user.setId(USER_ID);
        user.setLangKey("en");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(prayerAssignmentCalendarPdfService.renderOwnAssignmentsPdf(USER_ID, Locale.forLanguageTag("en"))).thenReturn(
            new byte[] { 1, 2 }
        );

        mockMvc
            .perform(get("/api/me/prayer-assignments/pdf"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"mes-affectations.pdf\""));

        verify(prayerAssignmentCalendarPdfService).renderOwnAssignmentsPdf(USER_ID, Locale.forLanguageTag("en"));
    }

    @Test
    void getOwnUpcomingAssignmentsPdf_fallsBackToDefaultLocaleWhenUserNotFound() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(prayerAssignmentCalendarPdfService.renderOwnAssignmentsPdf(eq(USER_ID), any())).thenReturn(new byte[] { 1 });

        mockMvc.perform(get("/api/me/prayer-assignments/pdf")).andExpect(status().isOk());

        verify(prayerAssignmentCalendarPdfService).renderOwnAssignmentsPdf(USER_ID, Locale.forLanguageTag("fr"));
    }

    @Test
    void getOwnUpcomingAssignments_returns500WhenSomehowUnauthenticated() throws Exception {
        // The security filter chain rejects unauthenticated /api/** requests before this
        // controller is ever reached in production; this proves the fallback is sound anyway.
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/me/prayer-assignments")).andExpect(status().is5xxServerError());
    }
}

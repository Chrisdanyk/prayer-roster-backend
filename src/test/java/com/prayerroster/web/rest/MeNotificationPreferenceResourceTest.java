package com.prayerroster.web.rest;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.NotificationPreferenceService;
import com.prayerroster.service.dto.NotificationPreferenceDTO;
import com.prayerroster.service.dto.UpdateNotificationPreferenceRequest;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import org.junit.jupiter.api.AfterEach;
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

@ExtendWith(MockitoExtension.class)
class MeNotificationPreferenceResourceTest {

    private static final String USER_ID = "sub-1";

    @Mock
    private NotificationPreferenceService notificationPreferenceService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new MeNotificationPreferenceResource(notificationPreferenceService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(googleJwt(USER_ID, "jean@example.com")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOwnPreference_returnsPreference() throws Exception {
        when(notificationPreferenceService.findOwn(USER_ID)).thenReturn(new NotificationPreferenceDTO(true));

        mockMvc.perform(get("/api/me/notification-preference")).andExpect(status().isOk()).andExpect(jsonPath("$.emailEnabled").value(true));
    }

    @Test
    void updateOwnPreference_returnsUpdatedPreference() throws Exception {
        UpdateNotificationPreferenceRequest request = new UpdateNotificationPreferenceRequest(false);
        when(notificationPreferenceService.update(USER_ID, false)).thenReturn(new NotificationPreferenceDTO(false));

        mockMvc
            .perform(
                put("/api/me/notification-preference")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailEnabled").value(false));
    }

    @Test
    void getOwnPreference_returns500WhenSomehowUnauthenticated() throws Exception {
        // The security filter chain rejects unauthenticated /api/** requests before this
        // controller is ever reached in production; this proves the fallback is sound anyway.
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/me/notification-preference")).andExpect(status().is5xxServerError());
    }
}

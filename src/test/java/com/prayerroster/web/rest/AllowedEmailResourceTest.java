package com.prayerroster.web.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.AllowedEmailService;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.service.dto.InviteEmailRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
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
class AllowedEmailResourceTest {

    @Mock
    private AllowedEmailService allowedEmailService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AllowedEmailResource(allowedEmailService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getAllowedEmails_returnsTheList() throws Exception {
        when(allowedEmailService.findAll()).thenReturn(List.of(new AllowedEmailDTO(1L, "jean@example.com", null)));

        mockMvc.perform(get("/api/allowed-emails")).andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("jean@example.com"));
    }

    @Test
    void inviteEmail_returns201() throws Exception {
        when(allowedEmailService.invite("jean@example.com")).thenReturn(new AllowedEmailDTO(1L, "jean@example.com", null));

        mockMvc
            .perform(
                post("/api/allowed-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new InviteEmailRequest("jean@example.com")))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("jean@example.com"));
    }

    @Test
    void inviteEmail_returns400OnDuplicate() throws Exception {
        when(allowedEmailService.invite("jean@example.com")).thenThrow(
            new BadRequestAlertException("duplicate", "allowedEmail", "duplicateEmail")
        );

        mockMvc
            .perform(
                post("/api/allowed-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new InviteEmailRequest("jean@example.com")))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void inviteEmail_returns400OnMalformedEmail() throws Exception {
        mockMvc
            .perform(
                post("/api/allowed-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new InviteEmailRequest("not-an-email")))
            )
            .andExpect(status().isBadRequest());

        verifyNoInteractions(allowedEmailService);
    }

    @Test
    void deleteAllowedEmail_returns204() throws Exception {
        mockMvc.perform(delete("/api/allowed-emails/{id}", 1L)).andExpect(status().isNoContent());

        verify(allowedEmailService).delete(1L);
    }
}

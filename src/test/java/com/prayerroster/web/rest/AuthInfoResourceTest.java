package com.prayerroster.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthInfoResourceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthInfoResource resource = new AuthInfoResource();
        ReflectionTestUtils.setField(resource, "issuer", "https://accounts.google.com");
        ReflectionTestUtils.setField(resource, "clientId", "test-client-id");
        mockMvc = MockMvcBuilders.standaloneSetup(resource).build();
    }

    @Test
    void getAuthInfo_returnsConfiguredIssuerAndClientId() throws Exception {
        mockMvc
            .perform(get("/api/auth-info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("https://accounts.google.com"))
            .andExpect(jsonPath("$.clientId").value("test-client-id"));
    }
}

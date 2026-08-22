package com.prayerroster.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prayerroster.service.UserManagementService;
import com.prayerroster.service.dto.AssignRoleRequest;
import com.prayerroster.service.dto.UpdateUserRequest;
import com.prayerroster.service.dto.UpdateUserStatusRequest;
import com.prayerroster.service.dto.UserDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
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
class UserResourceTest {

    @Mock
    private UserManagementService userManagementService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static UserDTO sampleUser() {
        return new UserDTO("sub-1", "jean@example.com", "Jean", "Dupont", true, true, false, "USER");
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new UserResource(userManagementService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    @Test
    void getUsers_returnsPageContentWithPaginationHeader() throws Exception {
        when(userManagementService.findAll(any())).thenReturn(new PageImpl<>(java.util.List.of(sampleUser()), PageRequest.of(0, 20), 1));

        mockMvc
            .perform(get("/api/users"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"))
            .andExpect(jsonPath("$[0].id").value("sub-1"));
    }

    @Test
    void getUser_returnsUser() throws Exception {
        when(userManagementService.findOne("sub-1")).thenReturn(sampleUser());

        mockMvc.perform(get("/api/users/sub-1")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("jean@example.com"));
    }

    @Test
    void getUser_returns404WhenNotFound() throws Exception {
        when(userManagementService.findOne("missing")).thenThrow(new EntityNotFoundException("User not found: missing"));

        mockMvc.perform(get("/api/users/missing")).andExpect(status().isNotFound());
    }

    @Test
    void updateUser_returnsUpdatedUser() throws Exception {
        UpdateUserRequest request = new UpdateUserRequest("Marie", "Curie", true, true);
        when(userManagementService.update(eq("sub-1"), any())).thenReturn(
            new UserDTO("sub-1", "jean@example.com", "Marie", "Curie", true, true, true, "USER")
        );

        mockMvc
            .perform(put("/api/users/sub-1").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Marie"));
    }

    @Test
    void updateStatus_returnsUpdatedUser() throws Exception {
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(false);
        when(userManagementService.updateStatus("sub-1", false)).thenReturn(
            new UserDTO("sub-1", "jean@example.com", "Jean", "Dupont", false, true, false, "USER")
        );

        mockMvc
            .perform(
                put("/api/users/sub-1/status").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void assignRole_returnsUpdatedUser() throws Exception {
        AssignRoleRequest request = new AssignRoleRequest("ADMIN");
        when(userManagementService.assignRole("sub-1", "ADMIN")).thenReturn(
            new UserDTO("sub-1", "jean@example.com", "Jean", "Dupont", true, true, false, "ADMIN")
        );

        mockMvc
            .perform(put("/api/users/sub-1/role").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roleName").value("ADMIN"));
    }

    @Test
    void assignRole_returns400WhenRoleUnknown() throws Exception {
        AssignRoleRequest request = new AssignRoleRequest("NOT_A_ROLE");
        when(userManagementService.assignRole("sub-1", "NOT_A_ROLE")).thenThrow(
            new BadRequestAlertException("Role not found", "user", "roleNotFound")
        );

        mockMvc
            .perform(put("/api/users/sub-1/role").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }
}

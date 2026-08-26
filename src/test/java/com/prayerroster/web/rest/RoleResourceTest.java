package com.prayerroster.web.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.RoleService;
import com.prayerroster.service.dto.CreateRoleRequest;
import com.prayerroster.service.dto.RoleDTO;
import com.prayerroster.service.dto.UpdateRoleRequest;
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
class RoleResourceTest {

    @Mock
    private RoleService roleService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new RoleResource(roleService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getRoles_returnsTheList() throws Exception {
        when(roleService.findAll()).thenReturn(List.of(new RoleDTO(1L, "ADMIN", "Gestion", List.of("USER_VIEW"), 3L)));

        mockMvc
            .perform(get("/api/roles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("ADMIN"))
            .andExpect(jsonPath("$[0].userCount").value(3));
    }

    @Test
    void createRole_returns201() throws Exception {
        when(roleService.create(new CreateRoleRequest("COORDINATOR", "Coordination", List.of("USER_VIEW")))).thenReturn(
            new RoleDTO(2L, "COORDINATOR", "Coordination", List.of("USER_VIEW"), 0L)
        );

        mockMvc
            .perform(
                post("/api/roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CreateRoleRequest("COORDINATOR", "Coordination", List.of("USER_VIEW"))))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("COORDINATOR"));
    }

    @Test
    void createRole_returns400OnABlankName() throws Exception {
        mockMvc
            .perform(
                post("/api/roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CreateRoleRequest("", null, List.of())))
            )
            .andExpect(status().isBadRequest());

        verifyNoInteractions(roleService);
    }

    @Test
    void updateRole_returns200() throws Exception {
        when(roleService.update(2L, new UpdateRoleRequest(null, "Coordination", List.of("USER_VIEW")))).thenReturn(
            new RoleDTO(2L, "COORDINATOR", "Coordination", List.of("USER_VIEW"), 1L)
        );

        mockMvc
            .perform(
                put("/api/roles/{id}", 2L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new UpdateRoleRequest(null, "Coordination", List.of("USER_VIEW"))))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description").value("Coordination"));
    }

    @Test
    void updateRole_returns200WhenRenamingACustomRole() throws Exception {
        when(roleService.update(2L, new UpdateRoleRequest("SUPERVISOR", "Coordination", List.of("USER_VIEW")))).thenReturn(
            new RoleDTO(2L, "SUPERVISOR", "Coordination", List.of("USER_VIEW"), 1L)
        );

        mockMvc
            .perform(
                put("/api/roles/{id}", 2L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new UpdateRoleRequest("SUPERVISOR", "Coordination", List.of("USER_VIEW"))))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("SUPERVISOR"));
    }

    @Test
    void updateRole_returns400WhenRenamingABaselineRole() throws Exception {
        when(roleService.update(1L, new UpdateRoleRequest("SUPERVISOR", null, List.of()))).thenThrow(
            new BadRequestAlertException("Baseline roles cannot be renamed", "role", "baselineRole")
        );

        mockMvc
            .perform(
                put("/api/roles/{id}", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new UpdateRoleRequest("SUPERVISOR", null, List.of())))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRole_returns204() throws Exception {
        mockMvc.perform(delete("/api/roles/{id}", 5L)).andExpect(status().isNoContent());

        verify(roleService).delete(5L);
    }

    @Test
    void deleteRole_returns400WhenServiceRejects() throws Exception {
        org.mockito.Mockito.doThrow(new BadRequestAlertException("in use", "role", "roleInUse")).when(roleService).delete(5L);

        mockMvc.perform(delete("/api/roles/{id}", 5L)).andExpect(status().isBadRequest());
    }
}

package com.prayerroster.web.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Permission;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PermissionResourceTest {

    @Mock
    private PermissionRepository permissionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PermissionResource(permissionRepository))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    void getPermissions_returnsTheCatalogue() throws Exception {
        when(permissionRepository.findAll()).thenReturn(List.of(permission(1L, "USER_VIEW"), permission(2L, "ROLE_UPDATE")));

        mockMvc
            .perform(get("/api/permissions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ROLE_UPDATE"))
            .andExpect(jsonPath("$[1].code").value("USER_VIEW"));
    }

    private Permission permission(Long id, String code) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setCode(code);
        return permission;
    }
}

package com.prayerroster.web.rest;

import com.prayerroster.service.UserManagementService;
import com.prayerroster.service.dto.AssignRoleRequest;
import com.prayerroster.service.dto.UpdateUserRequest;
import com.prayerroster.service.dto.UpdateUserStatusRequest;
import com.prayerroster.service.dto.UserDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/** Admin-facing user management. Every operation is permission-gated, never by role name. */
@RestController
@RequestMapping("/api/users")
public class UserResource {

    private final UserManagementService userManagementService;

    public UserResource(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public ResponseEntity<List<UserDTO>> getUsers(@PageableDefault(size = 20) Pageable pageable) {
        Page<UserDTO> page = userManagementService.findAll(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public UserDTO getUser(@PathVariable String id) {
        return userManagementService.findOne(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public UserDTO updateUser(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return userManagementService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public UserDTO updateStatus(@PathVariable String id, @Valid @RequestBody UpdateUserStatusRequest request) {
        return userManagementService.updateStatus(id, request.active());
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('PERM_USER_ROLE_ASSIGN')")
    public UserDTO assignRole(@PathVariable String id, @Valid @RequestBody AssignRoleRequest request) {
        return userManagementService.assignRole(id, request.roleName());
    }
}

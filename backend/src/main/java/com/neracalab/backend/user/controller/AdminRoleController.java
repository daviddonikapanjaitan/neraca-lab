package com.neracalab.backend.user.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.user.RoleService;
import com.neracalab.backend.user.UserDtos.PermissionView;
import com.neracalab.backend.user.UserDtos.RoleRequest;
import com.neracalab.backend.user.UserDtos.RoleView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role management (ADMIN permission).
 * <ul>
 *   <li>{@code GET /api/v1/admin/permissions} - the permissions a role can have</li>
 *   <li>{@code GET /api/v1/admin/roles}, {@code GET /api/v1/admin/roles/{id}} - with permissions and user count</li>
 *   <li>{@code POST /api/v1/admin/roles} - {@code {name, description, permissions}}; 201; 409 for a taken name</li>
 *   <li>{@code PUT /api/v1/admin/roles/{id}} - built-in role: description only</li>
 *   <li>{@code DELETE /api/v1/admin/roles/{id}} - 204; 409 while assigned to users; never the built-in role</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiresPermission(Permission.ADMIN)
public class AdminRoleController {

    private final RoleService roles;

    public AdminRoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping(path = "/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PermissionView> permissions() {
        return roles.permissions();
    }

    @GetMapping(path = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RoleView> list() {
        return roles.list();
    }

    @GetMapping(path = "/roles/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RoleView get(@PathVariable("id") long id) {
        return roles.get(id);
    }

    @PostMapping(path = "/roles", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RoleView> create(@Valid @RequestBody RoleRequest request) {
        RoleView role = roles.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/roles/" + role.id())).body(role);
    }

    @PutMapping(path = "/roles/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RoleView update(@PathVariable("id") long id, @Valid @RequestBody RoleRequest request, AuthenticatedUser actor) {
        return roles.update(id, request, actor);
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        roles.delete(id);
        return ResponseEntity.noContent().build();
    }
}

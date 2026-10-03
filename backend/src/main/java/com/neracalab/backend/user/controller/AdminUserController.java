package com.neracalab.backend.user.controller;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.neracalab.backend.auth.AuthInterceptor;
import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.user.UserDtos.CreateUserRequest;
import com.neracalab.backend.user.UserDtos.UpdateUserRequest;
import com.neracalab.backend.user.UserDtos.UserView;
import com.neracalab.backend.user.UserService;
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
 * User management (ADMIN permission).
 * <ul>
 *   <li>{@code GET /api/v1/admin/users}, {@code GET /api/v1/admin/users/{id}}</li>
 *   <li>{@code POST /api/v1/admin/users} - 201; 409 for a taken username / email</li>
 *   <li>{@code PUT /api/v1/admin/users/{id}} - every editable field (not the username); optional new password</li>
 *   <li>{@code DELETE /api/v1/admin/users/{id}} - 204; not the root user, not yourself</li>
 *   <li>{@code GET /api/v1/admin/users/{id}/avatar} - the user's profile picture</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiresPermission(Permission.ADMIN)
public class AdminUserController {

    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<UserView> list() {
        return users.list();
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView get(@PathVariable("id") long id) {
        return users.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        UserView user = users.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + user.id())).body(user);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView update(@PathVariable("id") long id, @Valid @RequestBody UpdateUserRequest request,
                           AuthenticatedUser actor, HttpServletRequest http) {
        return users.update(id, request, actor, (String) http.getAttribute(AuthInterceptor.TOKEN_ATTRIBUTE));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id, AuthenticatedUser actor) {
        users.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable("id") long id) {
        return AvatarResponses.of(users.avatar(id));
    }
}

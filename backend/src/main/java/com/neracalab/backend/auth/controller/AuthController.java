package com.neracalab.backend.auth.controller;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.neracalab.backend.auth.AuthService;
import com.neracalab.backend.auth.AuthService.Login;
import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.PublicAccess;
import com.neracalab.backend.auth.RequiresLogin;
import com.neracalab.backend.user.UserDtos.UserView;
import com.neracalab.backend.user.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login with username and password.
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} - {@code {username, password}}; 200 with the bearer token,
 *       its expiry and the user; 401 for a wrong username / password or a deactivated user</li>
 *   <li>{@code POST /api/v1/auth/logout} - ends the session of the bearer token (204, also when it is
 *       already invalid)</li>
 *   <li>{@code GET /api/v1/auth/me} - the logged-in user with roles and permissions</li>
 * </ul>
 * Every other API needs {@code Authorization: Bearer <token>}.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
    }

    /** @param token send as {@code Authorization: Bearer <token>} until {@code expiresAt} */
    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    private final AuthService auth;
    private final UserService users;

    public AuthController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    @PublicAccess
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Login login = auth.login(request.username(), request.password());
        return new LoginResponse(login.session().token(), login.session().expiresAt(), users.get(login.userId()));
    }

    /** Public, so a client can always log out, even with an expired token. */
    @PublicAccess
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            auth.logout(header.substring(7).trim());
        }
        return ResponseEntity.noContent().build();
    }

    @RequiresLogin
    @GetMapping("/me")
    public UserView me(AuthenticatedUser user) {
        return users.get(user.userId());
    }
}

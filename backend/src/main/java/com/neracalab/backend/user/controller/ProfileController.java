package com.neracalab.backend.user.controller;

import java.io.IOException;

import jakarta.validation.Valid;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.RequiresLogin;
import com.neracalab.backend.user.UserDtos.ProfileUpdateRequest;
import com.neracalab.backend.user.UserDtos.UserView;
import com.neracalab.backend.user.UserService;
import com.neracalab.backend.web.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The profile of the logged-in user (any logged-in user, no permission needed).
 * <ul>
 *   <li>{@code GET /api/v1/profile} - the own user</li>
 *   <li>{@code PUT /api/v1/profile} - {@code {address, phone, dob}}; username and email cannot be changed (400)</li>
 *   <li>{@code GET / PUT (multipart "file") / DELETE /api/v1/profile/avatar} - the profile picture
 *       (PNG, JPEG, WebP or GIF, at most 2 MB)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiresLogin
public class ProfileController {

    private final UserService users;

    public ProfileController(UserService users) {
        this.users = users;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView profile(AuthenticatedUser user) {
        return users.get(user.userId());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView update(AuthenticatedUser user, @Valid @RequestBody ProfileUpdateRequest request) {
        return users.updateProfile(user, request);
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar(AuthenticatedUser user) {
        return AvatarResponses.of(users.avatar(user.userId()));
    }

    @PutMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView uploadAvatar(AuthenticatedUser user, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("The picture is empty");
        }
        return users.setAvatar(user.userId(), file.getBytes());
    }

    @DeleteMapping(path = "/avatar", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserView deleteAvatar(AuthenticatedUser user) {
        return users.clearAvatar(user.userId());
    }
}

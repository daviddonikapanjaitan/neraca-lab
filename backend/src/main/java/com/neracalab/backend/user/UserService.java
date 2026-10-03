package com.neracalab.backend.user;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.auth.AuthService;
import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.user.UserDtos.CreateUserRequest;
import com.neracalab.backend.user.UserDtos.ProfileUpdateRequest;
import com.neracalab.backend.user.UserDtos.RoleRef;
import com.neracalab.backend.user.UserDtos.UpdateUserRequest;
import com.neracalab.backend.user.UserDtos.UserView;
import com.neracalab.backend.user.UserRepository.Avatar;
import com.neracalab.backend.user.UserRepository.Profile;
import com.neracalab.backend.user.UserRepository.UserRow;
import com.neracalab.backend.web.ApiException;

/**
 * User management (admin) and the own profile. Rules beyond the request validation:
 * <ul>
 *   <li>username and email are unique (case-insensitive, stored lower case); the username never changes</li>
 *   <li>a user has at least one role, every role id must exist</li>
 *   <li>the root user cannot be deleted, deactivated or lose the built-in Administrator role</li>
 *   <li>administrators cannot delete or deactivate themselves or remove their own ADMIN permission</li>
 *   <li>a new password or a deactivation ends the user's sessions (except the acting session)</li>
 *   <li>on their own profile users change only address, phone, date of birth and avatar</li>
 * </ul>
 */
@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final AuthService auth;

    public UserService(UserRepository users, RoleRepository roles, AuthService auth) {
        this.users = users;
        this.roles = roles;
        this.auth = auth;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return views(users.list());
    }

    @Transactional(readOnly = true)
    public UserView get(long userId) {
        return views(List.of(row(userId))).getFirst();
    }

    public Avatar avatar(long userId) {
        return users.avatar(userId)
                .orElseThrow(() -> ApiException.notFound("Avatar not found", "User " + userId + " has no profile picture"));
    }

    // ------------------------------------------------------------------ admin

    @Transactional
    public UserView create(CreateUserRequest request) {
        String username = normalize(request.username());
        String email = normalize(request.email());
        Set<Long> roleIds = new LinkedHashSet<>(request.roleIds());
        if (users.usernameTaken(username)) {
            throw ApiException.conflict("The username '" + username + "' is already taken");
        }
        if (users.emailTaken(email, null)) {
            throw ApiException.conflict("The email " + email + " is already used by another user");
        }
        requireRolesExist(roleIds);
        checkPassword(request.password());
        long userId;
        try {
            userId = users.insert(username, email, auth.hashPassword(request.password()), profile(request.fullName(),
                    request.address(), request.phone(), request.dob()), request.active() == null || request.active(), false);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        users.setRoles(userId, roleIds);
        return get(userId);
    }

    /** @param actorToken session of the acting administrator (kept when they change their own password) */
    @Transactional
    public UserView update(long userId, UpdateUserRequest request, AuthenticatedUser actor, String actorToken) {
        UserRow current = row(userId);
        if (request.username() != null && !normalize(request.username()).equals(current.username())) {
            throw ApiException.badRequest("The username cannot be changed");
        }
        String email = normalize(request.email());
        Set<Long> roleIds = new LinkedHashSet<>(request.roleIds());
        boolean active = request.active();
        if (users.emailTaken(email, userId)) {
            throw ApiException.conflict("The email " + email + " is already used by another user");
        }
        requireRolesExist(roleIds);
        if (current.root()) {
            if (!active) {
                throw ApiException.badRequest("The root user cannot be deactivated");
            }
            long systemRole = roles.systemRoleId().orElseThrow();
            if (!roleIds.contains(systemRole)) {
                throw ApiException.badRequest("The root user must keep the built-in Administrator role");
            }
        }
        boolean self = userId == actor.userId();
        if (self) {
            if (!active) {
                throw ApiException.badRequest("You cannot deactivate your own account");
            }
            if (!permissionsOf(roleIds).contains(Permission.ADMIN)) {
                throw ApiException.badRequest("You cannot remove your own admin access: keep a role with the ADMIN permission");
            }
        }
        boolean newPassword = request.password() != null && !request.password().isEmpty();
        if (newPassword) {
            checkPassword(request.password());
        }
        try {
            users.update(userId, email, profile(request.fullName(), request.address(), request.phone(), request.dob()), active);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        users.setRoles(userId, roleIds);
        if (newPassword) {
            users.updatePassword(userId, auth.hashPassword(request.password()));
        }
        if (newPassword || !active) {
            auth.endSessions(userId, self ? actorToken : null);
        }
        return get(userId);
    }

    @Transactional
    public void delete(long userId, AuthenticatedUser actor) {
        UserRow current = row(userId);
        if (current.root()) {
            throw ApiException.badRequest("The root user cannot be deleted");
        }
        if (userId == actor.userId()) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        users.delete(userId);   // sessions and role assignments are deleted by ON DELETE CASCADE
    }

    // ------------------------------------------------------------------ own profile

    @Transactional
    public UserView updateProfile(AuthenticatedUser actor, ProfileUpdateRequest request) {
        UserRow current = row(actor.userId());
        if (request.username() != null && !normalize(request.username()).equals(current.username())) {
            throw ApiException.badRequest("The username cannot be changed");
        }
        if (request.email() != null && !normalize(request.email()).equals(current.email())) {
            throw ApiException.badRequest("The email cannot be changed");
        }
        users.updateOwnProfile(actor.userId(), blankToNull(request.address()), blankToNull(request.phone()), request.dob());
        return get(actor.userId());
    }

    @Transactional
    public UserView setAvatar(long userId, byte[] content) {
        row(userId);
        if (content.length == 0) {
            throw ApiException.badRequest("The picture is empty");
        }
        if (content.length > AvatarImages.MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "Picture too large",
                    "The picture is larger than " + (AvatarImages.MAX_BYTES / (1024 * 1024)) + " MB");
        }
        String contentType = AvatarImages.contentType(content);
        if (contentType == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported picture",
                    "Only PNG, JPEG, WebP and GIF pictures are accepted");
        }
        users.setAvatar(userId, content, contentType);
        return get(userId);
    }

    @Transactional
    public UserView clearAvatar(long userId) {
        row(userId);
        users.clearAvatar(userId);
        return get(userId);
    }

    // ------------------------------------------------------------------ helpers

    private UserRow row(long userId) {
        return users.find(userId).orElseThrow(() -> ApiException.notFound("User not found", "No user " + userId));
    }

    private List<UserView> views(List<UserRow> rows) {
        List<Long> ids = rows.stream().map(UserRow::userId).toList();
        Map<Long, List<UserRepository.RoleRef>> rolesByUser = users.roles(ids);
        Map<Long, Set<Permission>> permissionsByUser = users.permissions(ids);
        return rows.stream().map(u -> new UserView(u.userId(), u.username(), u.email(), u.fullName(), u.address(),
                u.phone(), u.dob(), u.active(), u.root(), u.hasAvatar(), u.avatarUpdatedAt(),
                rolesByUser.getOrDefault(u.userId(), List.of()).stream()
                        .map(r -> new RoleRef(r.roleId(), r.name(), r.system())).toList(),
                new TreeSet<>(permissionsByUser.getOrDefault(u.userId(), Set.of())),
                u.createdAt(), u.updatedAt())).toList();
    }

    private void requireRolesExist(Set<Long> roleIds) {
        Set<Long> missing = new TreeSet<>(roleIds);
        missing.removeAll(roles.existing(roleIds));
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Unknown role id(s): " + missing);
        }
    }

    private Set<Permission> permissionsOf(Set<Long> roleIds) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        roles.permissions(roleIds).values().forEach(permissions::addAll);
        return permissions;
    }

    /** 8-72 characters and at most 72 bytes (the BCrypt limit). */
    private static void checkPassword(String password) {
        if (password == null || password.length() < 8) {
            throw ApiException.badRequest("password: must be 8-72 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("password: must be at most 72 bytes");
        }
    }

    private static Profile profile(String fullName, String address, String phone, LocalDate dob) {
        return new Profile(blankToNull(fullName), blankToNull(address), blankToNull(phone), dob);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 409 for a unique constraint hit by a concurrent request (the checks above ran before it). */
    static ApiException duplicate(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("uq_users_username")) {
            return ApiException.conflict("The username is already taken");
        }
        if (message.contains("uq_users_email")) {
            return ApiException.conflict("The email is already used by another user");
        }
        if (message.contains("uq_roles_name")) {
            return ApiException.conflict("A role with this name already exists");
        }
        return ApiException.conflict("The data conflicts with an existing record");
    }
}

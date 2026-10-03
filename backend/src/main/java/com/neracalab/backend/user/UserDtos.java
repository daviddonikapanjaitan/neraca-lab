package com.neracalab.backend.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.neracalab.backend.auth.Permission;

/** Request and response bodies of the user, role and profile APIs. */
public final class UserDtos {

    private UserDtos() {
    }

    static final String USERNAME_PATTERN = "^[A-Za-z0-9._-]{3,50}$";
    static final String USERNAME_MESSAGE = "3-50 letters, digits, '.', '_' or '-'";
    /** Empty = no phone (stored as NULL). */
    static final String PHONE_PATTERN = "^$|^\\+?[0-9 ()./-]{6,30}$";
    static final String PHONE_MESSAGE = "6-30 digits, spaces or ( ) . / -, optionally starting with +";

    /** Surrounding blanks are removed before validation (never applied to passwords). */
    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    // ------------------------------------------------------------------ users

    /**
     * A user as the APIs return it.
     *
     * @param root        the root user (admin): cannot be deleted, deactivated or lose the Administrator role
     * @param hasAvatar   a profile picture is stored ({@code GET /api/v1/profile/avatar}, admin:
     *                    {@code GET /api/v1/admin/users/{id}/avatar})
     * @param permissions union of the permissions of all roles
     */
    public record UserView(long id, String username, String email, String fullName, String address, String phone,
                           LocalDate dob, boolean active, boolean root, boolean hasAvatar, Instant avatarUpdatedAt,
                           List<RoleRef> roles, Set<Permission> permissions, Instant createdAt, Instant updatedAt) {
    }

    /** A role as listed on a user. */
    public record RoleRef(long id, String name, boolean system) {
    }

    /** {@code POST /api/v1/admin/users} */
    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password,
            @Size(max = 150) String fullName,
            @Size(max = 500) String address,
            @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String phone,
            @Past LocalDate dob,
            Boolean active,
            @NotEmpty(message = "a user needs at least one role") Set<@NotNull Long> roleIds) {

        public CreateUserRequest {
            username = trim(username);
            email = trim(email);
            fullName = trim(fullName);
            address = trim(address);
            phone = trim(phone);
        }
    }

    /**
     * {@code PUT /api/v1/admin/users/{id}}: replaces every editable field. The username cannot be
     * changed. {@code password} null or empty keeps the password.
     */
    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 150) String fullName,
            @Size(max = 500) String address,
            @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String phone,
            @Past LocalDate dob,
            @NotNull Boolean active,
            @NotEmpty(message = "a user needs at least one role") Set<@NotNull Long> roleIds,
            @Size(max = 72, message = "must be 8-72 characters") String password,
            String username) {

        public UpdateUserRequest {
            email = trim(email);
            fullName = trim(fullName);
            address = trim(address);
            phone = trim(phone);
            username = trim(username);
        }
    }

    /**
     * {@code PUT /api/v1/profile}: what users may change on their own profile. {@code username} and
     * {@code email} are only accepted to reject them explicitly (they cannot be changed).
     */
    public record ProfileUpdateRequest(
            @Size(max = 500) String address,
            @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String phone,
            @Past LocalDate dob,
            String username,
            String email) {

        public ProfileUpdateRequest {
            address = trim(address);
            phone = trim(phone);
            username = trim(username);
            email = trim(email);
        }
    }

    // ------------------------------------------------------------------ roles

    /** A role as the APIs return it; {@code system} = the built-in Administrator role. */
    public record RoleView(long id, String name, String description, boolean system, Set<Permission> permissions,
                           long userCount, Instant createdAt, Instant updatedAt) {
    }

    /** {@code POST /api/v1/admin/roles}, {@code PUT /api/v1/admin/roles/{id}} */
    public record RoleRequest(
            @NotBlank @Size(max = 50) String name,
            @Size(max = 255) String description,
            @NotEmpty(message = "a role needs at least one permission") Set<@NotNull Permission> permissions) {

        public RoleRequest {
            name = trim(name);
            description = trim(description);
        }
    }

    /** {@code GET /api/v1/admin/permissions} */
    public record PermissionView(Permission code, String label, String description) {
    }
}

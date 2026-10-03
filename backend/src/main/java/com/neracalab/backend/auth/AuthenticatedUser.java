package com.neracalab.backend.auth;

import java.util.Set;

/**
 * The user of the current request, resolved from its session token. Permissions are read from the
 * database on every request, so a role change applies at once. A controller method receives it as
 * a parameter of this type.
 */
public record AuthenticatedUser(long userId, String username, Set<Permission> permissions) {

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}

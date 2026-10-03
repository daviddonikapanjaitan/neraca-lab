package com.neracalab.backend.auth;

import org.springframework.http.HttpStatus;

import com.neracalab.backend.web.ApiException;

/** 401 and 403 of the access check. */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** 401: no token, an unknown or expired token, or a deactivated user. */
    public static ApiException notAuthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated",
                "Log in to use this API (missing, invalid or expired session)");
    }

    /** 401: login with a wrong username or password. */
    public static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Login failed", "Invalid username or password");
    }

    /** 401: login of a deactivated user (the password was right). */
    public static ApiException deactivated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Login failed",
                "This account is deactivated. Contact an administrator.");
    }

    /** 403: logged in, but without the permission the endpoint needs. */
    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "Forbidden", detail);
    }
}

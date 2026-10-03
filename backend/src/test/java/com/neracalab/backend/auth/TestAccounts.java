package com.neracalab.backend.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Users and roles for one test, named with a random suffix and deleted by {@link #cleanup()}
 * (sessions and role assignments go with them by ON DELETE CASCADE).
 */
public final class TestAccounts {

    /** Password of every user created here. */
    public static final String PASSWORD = "secret-password";

    private final ApplicationContext context;
    private final JdbcClient jdbc;
    private final String passwordHash;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> users = new ArrayList<>();
    private final List<Long> roles = new ArrayList<>();

    public TestAccounts(ApplicationContext context) {
        this.context = context;
        this.jdbc = context.getBean(JdbcClient.class);
        this.passwordHash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
    }

    /** Unique name for this test: "t-<name>-<suffix>" (lower case, valid as username and role name). */
    public String name(String name) {
        return "t-" + name + "-" + suffix;
    }

    public long role(String name, Permission... permissions) {
        long roleId = jdbc.sql("INSERT INTO roles (name) VALUES (:n) RETURNING role_id")
                .param("n", name(name)).query(Long.class).single();
        roles.add(roleId);
        for (Permission permission : permissions) {
            jdbc.sql("INSERT INTO role_permissions (role_id, permission) VALUES (:r, :p)")
                    .param("r", roleId).param("p", permission.name()).update();
        }
        return roleId;
    }

    public long user(String name, long... roleIds) {
        String username = name(name);
        long userId = jdbc.sql("INSERT INTO users (username, email, password_hash) VALUES (:u, :e, :h) RETURNING user_id")
                .param("u", username).param("e", username + "@test.local").param("h", passwordHash)
                .query(Long.class).single();
        users.add(userId);
        for (long roleId : roleIds) {
            jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", userId).param("r", roleId).update();
        }
        return userId;
    }

    /** Registers a user created through the API for deletion. */
    public void track(long userId) {
        users.add(userId);
    }

    /** Registers a role created through the API for deletion. */
    public void trackRole(long roleId) {
        roles.add(roleId);
    }

    public String token(long userId) {
        return TestLogins.token(context, userId);
    }

    public void cleanup() {
        if (!users.isEmpty()) {
            jdbc.sql("DELETE FROM users WHERE user_id IN (:ids) AND NOT root").param("ids", users).update();
        }
        if (!roles.isEmpty()) {
            jdbc.sql("DELETE FROM roles WHERE role_id IN (:ids) AND NOT system").param("ids", roles).update();
        }
        // users created through the API whose ids were not tracked (e.g. after a failed assertion)
        jdbc.sql("DELETE FROM users WHERE username LIKE :p AND NOT root").param("p", "t-%-" + suffix).update();
        jdbc.sql("DELETE FROM roles WHERE lower(name) LIKE :p AND NOT system").param("p", "t-%-" + suffix).update();
    }
}

package com.neracalab.backend.auth;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.user.RoleRepository;
import com.neracalab.backend.user.UserRepository;
import com.neracalab.backend.user.UserRepository.Profile;

/**
 * At every start (after the SQL scripts, before the web server accepts requests) makes sure that
 * <ol>
 *   <li>the built-in {@value #ADMIN_ROLE} role exists with every permission,</li>
 *   <li>the root user {@value #ROOT_USERNAME} exists (created once with the password
 *       {@code neracalab.auth.root-password}, default {@code admin}, and dummy profile data; an
 *       existing root user, its password and its profile are never changed),</li>
 *   <li>the root user is active and has the {@value #ADMIN_ROLE} role.</li>
 * </ol>
 */
@Component
public class AuthBootstrap implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    public static final String ROOT_USERNAME = "admin";
    public static final String ADMIN_ROLE = "Administrator";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final RoleRepository roles;
    private final AuthService auth;
    private final AuthProperties properties;
    private final TransactionTemplate transaction;

    public AuthBootstrap(JdbcClient jdbc, UserRepository users, RoleRepository roles, AuthService auth,
                         AuthProperties properties, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.users = users;
        this.roles = roles;
        this.auth = auth;
        this.properties = properties;
        this.transaction = transaction;
    }

    @Override
    public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            long roleId = ensureAdminRole();
            long rootId = ensureRootUser();
            jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                    .param("u", rootId).param("r", roleId).update();
        });
    }

    private long ensureAdminRole() {
        Optional<Long> existing = roles.systemRoleId();
        long roleId;
        if (existing.isPresent()) {
            roleId = existing.get();
        } else {
            // a role created by hand with the same name becomes the built-in one
            Optional<Long> sameName = jdbc.sql("SELECT role_id FROM roles WHERE lower(name) = lower(:n)")
                    .param("n", ADMIN_ROLE).query(Long.class).optional();
            if (sameName.isPresent()) {
                roleId = sameName.get();
                jdbc.sql("UPDATE roles SET system = TRUE, updated_at = now() WHERE role_id = :id").param("id", roleId).update();
            } else {
                roleId = roles.insert(ADMIN_ROLE, "Built-in role with every permission: admin center, ingestion and companies", true);
            }
            log.info("Created the built-in {} role", ADMIN_ROLE);
        }
        for (Permission permission : EnumSet.allOf(Permission.class)) {
            jdbc.sql("INSERT INTO role_permissions (role_id, permission) VALUES (:r, :p) ON CONFLICT DO NOTHING")
                    .param("r", roleId).param("p", permission.name()).update();
        }
        return roleId;
    }

    private long ensureRootUser() {
        Optional<Long> root = users.rootUserId();
        if (root.isPresent()) {
            jdbc.sql("UPDATE users SET active = TRUE WHERE user_id = :id AND NOT active").param("id", root.get()).update();
            return root.get();
        }
        Optional<Long> sameName = jdbc.sql("SELECT user_id FROM users WHERE username = :u")
                .param("u", ROOT_USERNAME).query(Long.class).optional();
        if (sameName.isPresent()) {
            jdbc.sql("UPDATE users SET root = TRUE, active = TRUE, updated_at = now() WHERE user_id = :id")
                    .param("id", sameName.get()).update();
            log.info("User {} is now the root user", ROOT_USERNAME);
            return sameName.get();
        }
        long userId = users.insert(ROOT_USERNAME, "admin@neracalab.local", auth.hashPassword(properties.rootPassword()),
                new Profile("Neraca Lab Administrator", "Jl. Jenderal Sudirman Kav. 52-53, Jakarta Selatan 12190",
                        "+62 21 5150 1000", LocalDate.of(1990, 1, 1)),
                true, true);
        log.warn("Created the root user '{}' with the configured initial password; change it in User Management",
                ROOT_USERNAME);
        return userId;
    }
}

package com.neracalab.backend.auth;

import java.sql.Array;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code user_sessions}: login sessions, looked up by the SHA-256 of the bearer token. */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tokenHash, long userId, Instant expiresAt) {
        jdbc.sql("INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (:h, :u, :e)")
                .param("h", tokenHash).param("u", userId).param("e", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The user of an unexpired session, if the user is active, with the permissions of all their
     * roles (read now, so role changes apply to existing sessions at once).
     */
    public Optional<AuthenticatedUser> findUser(String tokenHash) {
        return jdbc.sql("""
                        SELECT u.user_id, u.username,
                               array_remove(array_agg(DISTINCT rp.permission), NULL) AS permissions
                        FROM user_sessions s
                        JOIN users u ON u.user_id = s.user_id
                        LEFT JOIN user_roles ur ON ur.user_id = u.user_id
                        LEFT JOIN role_permissions rp ON rp.role_id = ur.role_id
                        WHERE s.token_hash = :h AND s.expires_at > now() AND u.active
                        GROUP BY u.user_id, u.username""")
                .param("h", tokenHash)
                .query((rs, i) -> {
                    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
                    Array array = rs.getArray("permissions");
                    if (array != null) {
                        for (Object value : (Object[]) array.getArray()) {
                            permissions.add(Permission.valueOf((String) value));
                        }
                    }
                    return new AuthenticatedUser(rs.getLong("user_id"), rs.getString("username"), Set.copyOf(permissions));
                })
                .optional();
    }

    public int delete(String tokenHash) {
        return jdbc.sql("DELETE FROM user_sessions WHERE token_hash = :h").param("h", tokenHash).update();
    }

    /** Ends every session of a user except {@code keepTokenHash} (null = all). */
    public int deleteForUser(long userId, String keepTokenHash) {
        return jdbc.sql("DELETE FROM user_sessions WHERE user_id = :u AND token_hash IS DISTINCT FROM :keep")
                .param("u", userId).param("keep", keepTokenHash, Types.CHAR).update();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM user_sessions WHERE expires_at <= now()").update();
    }
}

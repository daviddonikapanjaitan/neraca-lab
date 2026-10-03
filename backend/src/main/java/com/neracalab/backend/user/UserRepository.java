package com.neracalab.backend.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.auth.Permission;

/** {@code users} and {@code user_roles}. Usernames and emails are passed in already normalised (lower case). */
@Repository
public class UserRepository {

    private static final String COLUMNS = """
            user_id, username, email, full_name, address, phone, dob, active, root,
            avatar_content_type IS NOT NULL AS has_avatar, avatar_updated_at, created_at, updated_at""";

    /** A user without password and avatar bytes. */
    public record UserRow(long userId, String username, String email, String fullName, String address, String phone,
                          LocalDate dob, boolean active, boolean root, boolean hasAvatar, Instant avatarUpdatedAt,
                          Instant createdAt, Instant updatedAt) {
    }

    /** What the login needs. */
    public record Credentials(long userId, String passwordHash, boolean active) {
    }

    /** A role as listed on a user. */
    public record RoleRef(long roleId, String name, boolean system) {
    }

    /** Profile fields; null = not given. */
    public record Profile(String fullName, String address, String phone, LocalDate dob) {
    }

    public record Avatar(byte[] content, String contentType, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    public List<UserRow> list() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users ORDER BY root DESC, username")
                .query((rs, i) -> row(rs))
                .list();
    }

    public Optional<UserRow> find(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE user_id = :id")
                .param("id", userId)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public Optional<Credentials> credentials(String username) {
        return jdbc.sql("SELECT user_id, password_hash, active FROM users WHERE username = :u")
                .param("u", username)
                .query((rs, i) -> new Credentials(rs.getLong("user_id"), rs.getString("password_hash"), rs.getBoolean("active")))
                .optional();
    }

    public Optional<Long> rootUserId() {
        return jdbc.sql("SELECT user_id FROM users WHERE root").query(Long.class).optional();
    }

    public boolean usernameTaken(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :u)")
                .param("u", username).query(Boolean.class).single();
    }

    /** {@code excludeUserId} null = any user. */
    public boolean emailTaken(String email, Long excludeUserId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE email = :e AND user_id IS DISTINCT FROM :id)")
                .param("e", email).param("id", excludeUserId, Types.BIGINT).query(Boolean.class).single();
    }

    /** Roles per user, ordered by role name; users without roles are missing from the map. */
    public Map<Long, List<RoleRef>> roles(Collection<Long> userIds) {
        Map<Long, List<RoleRef>> roles = new HashMap<>();
        if (userIds.isEmpty()) {
            return roles;
        }
        jdbc.sql("""
                        SELECT ur.user_id, r.role_id, r.name, r.system
                        FROM user_roles ur JOIN roles r ON r.role_id = ur.role_id
                        WHERE ur.user_id IN (:ids)
                        ORDER BY lower(r.name)""")
                .param("ids", userIds)
                .query((rs, i) -> {
                    roles.computeIfAbsent(rs.getLong("user_id"), id -> new ArrayList<>())
                            .add(new RoleRef(rs.getLong("role_id"), rs.getString("name"), rs.getBoolean("system")));
                    return null;
                })
                .list();
        return roles;
    }

    /** Permissions per user (union of their roles); users without permissions are missing from the map. */
    public Map<Long, Set<Permission>> permissions(Collection<Long> userIds) {
        Map<Long, Set<Permission>> permissions = new HashMap<>();
        if (userIds.isEmpty()) {
            return permissions;
        }
        jdbc.sql("""
                        SELECT DISTINCT ur.user_id, rp.permission
                        FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id
                        WHERE ur.user_id IN (:ids)""")
                .param("ids", userIds)
                .query((rs, i) -> {
                    permissions.computeIfAbsent(rs.getLong("user_id"), id -> EnumSet.noneOf(Permission.class))
                            .add(Permission.valueOf(rs.getString("permission")));
                    return null;
                })
                .list();
        return permissions;
    }

    public Optional<Avatar> avatar(long userId) {
        return jdbc.sql("SELECT avatar, avatar_content_type, avatar_updated_at FROM users WHERE user_id = :id AND avatar IS NOT NULL")
                .param("id", userId)
                .query((rs, i) -> new Avatar(rs.getBytes("avatar"), rs.getString("avatar_content_type"),
                        instant(rs, "avatar_updated_at")))
                .optional();
    }

    // ------------------------------------------------------------------ writes

    public long insert(String username, String email, String passwordHash, Profile profile, boolean active, boolean root) {
        return jdbc.sql("""
                        INSERT INTO users (username, email, password_hash, full_name, address, phone, dob, active, root)
                        VALUES (:username, :email, :hash, :fullName, :address, :phone, :dob, :active, :root)
                        RETURNING user_id""")
                .param("username", username)
                .param("email", email)
                .param("hash", passwordHash)
                .param("fullName", profile.fullName(), Types.VARCHAR)
                .param("address", profile.address(), Types.VARCHAR)
                .param("phone", profile.phone(), Types.VARCHAR)
                .param("dob", profile.dob(), Types.DATE)
                .param("active", active)
                .param("root", root)
                .query(Long.class)
                .single();
    }

    /** Everything an administrator may change (not the username). */
    public void update(long userId, String email, Profile profile, boolean active) {
        jdbc.sql("""
                        UPDATE users SET email = :email, full_name = :fullName, address = :address, phone = :phone,
                                         dob = :dob, active = :active, updated_at = now()
                        WHERE user_id = :id""")
                .param("id", userId)
                .param("email", email)
                .param("fullName", profile.fullName(), Types.VARCHAR)
                .param("address", profile.address(), Types.VARCHAR)
                .param("phone", profile.phone(), Types.VARCHAR)
                .param("dob", profile.dob(), Types.DATE)
                .param("active", active)
                .update();
    }

    /** What a user may change on their own profile. */
    public void updateOwnProfile(long userId, String address, String phone, LocalDate dob) {
        jdbc.sql("UPDATE users SET address = :address, phone = :phone, dob = :dob, updated_at = now() WHERE user_id = :id")
                .param("id", userId)
                .param("address", address, Types.VARCHAR)
                .param("phone", phone, Types.VARCHAR)
                .param("dob", dob, Types.DATE)
                .update();
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :hash, updated_at = now() WHERE user_id = :id")
                .param("id", userId).param("hash", passwordHash).update();
    }

    public void setAvatar(long userId, byte[] content, String contentType) {
        jdbc.sql("""
                        UPDATE users SET avatar = :content, avatar_content_type = :type, avatar_updated_at = now(),
                                         updated_at = now()
                        WHERE user_id = :id""")
                .param("id", userId).param("content", content).param("type", contentType).update();
    }

    public void clearAvatar(long userId) {
        jdbc.sql("""
                        UPDATE users SET avatar = NULL, avatar_content_type = NULL, avatar_updated_at = now(),
                                         updated_at = now()
                        WHERE user_id = :id""")
                .param("id", userId).update();
    }

    /** Replaces the roles of a user. */
    public void setRoles(long userId, Collection<Long> roleIds) {
        if (roleIds.isEmpty()) {
            jdbc.sql("DELETE FROM user_roles WHERE user_id = :id").param("id", userId).update();
            return;
        }
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :id AND role_id NOT IN (:roles)")
                .param("id", userId).param("roles", List.copyOf(roleIds)).update();
        for (Long roleId : roleIds) {
            jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:id, :role) ON CONFLICT DO NOTHING")
                    .param("id", userId).param("role", roleId).update();
        }
    }

    public int delete(long userId) {
        return jdbc.sql("DELETE FROM users WHERE user_id = :id").param("id", userId).update();
    }

    // ------------------------------------------------------------------ mapping

    private static UserRow row(ResultSet rs) throws SQLException {
        return new UserRow(rs.getLong("user_id"), rs.getString("username"), rs.getString("email"),
                rs.getString("full_name"), rs.getString("address"), rs.getString("phone"),
                rs.getObject("dob", LocalDate.class), rs.getBoolean("active"), rs.getBoolean("root"),
                rs.getBoolean("has_avatar"), instant(rs, "avatar_updated_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

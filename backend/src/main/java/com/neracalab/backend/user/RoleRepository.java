package com.neracalab.backend.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.auth.Permission;

/** {@code roles} and {@code role_permissions}. */
@Repository
public class RoleRepository {

    private static final String SELECT = """
            SELECT r.role_id, r.name, r.description, r.system, r.created_at, r.updated_at,
                   (SELECT count(*) FROM user_roles ur WHERE ur.role_id = r.role_id) AS user_count
            FROM roles r""";

    public record RoleRow(long roleId, String name, String description, boolean system, long userCount,
                          Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    /** The built-in role first, then by name. */
    public List<RoleRow> list() {
        return jdbc.sql(SELECT + " ORDER BY r.system DESC, lower(r.name)").query((rs, i) -> row(rs)).list();
    }

    public Optional<RoleRow> find(long roleId) {
        return jdbc.sql(SELECT + " WHERE r.role_id = :id").param("id", roleId).query((rs, i) -> row(rs)).optional();
    }

    public Optional<Long> systemRoleId() {
        return jdbc.sql("SELECT role_id FROM roles WHERE system").query(Long.class).optional();
    }

    /** {@code excludeRoleId} null = any role; compared ignoring case. */
    public boolean nameTaken(String name, Long excludeRoleId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM roles WHERE lower(name) = lower(:n) AND role_id IS DISTINCT FROM :id)")
                .param("n", name).param("id", excludeRoleId, Types.BIGINT).query(Boolean.class).single();
    }

    /** The ids of {@code roleIds} that exist. */
    public Set<Long> existing(Collection<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT role_id FROM roles WHERE role_id IN (:ids)")
                .param("ids", List.copyOf(roleIds)).query(Long.class).list());
    }

    /** Permissions per role; roles without permissions are missing from the map. */
    public Map<Long, Set<Permission>> permissions(Collection<Long> roleIds) {
        Map<Long, Set<Permission>> permissions = new HashMap<>();
        if (roleIds.isEmpty()) {
            return permissions;
        }
        jdbc.sql("SELECT role_id, permission FROM role_permissions WHERE role_id IN (:ids)")
                .param("ids", List.copyOf(roleIds))
                .query((rs, i) -> {
                    permissions.computeIfAbsent(rs.getLong("role_id"), id -> EnumSet.noneOf(Permission.class))
                            .add(Permission.valueOf(rs.getString("permission")));
                    return null;
                })
                .list();
        return permissions;
    }

    /** Role ids of a user. */
    public Set<Long> roleIdsOf(long userId) {
        return new HashSet<>(jdbc.sql("SELECT role_id FROM user_roles WHERE user_id = :id")
                .param("id", userId).query(Long.class).list());
    }

    // ------------------------------------------------------------------ writes

    public long insert(String name, String description, boolean system) {
        return jdbc.sql("INSERT INTO roles (name, description, system) VALUES (:name, :description, :system) RETURNING role_id")
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("system", system)
                .query(Long.class)
                .single();
    }

    public void update(long roleId, String name, String description) {
        jdbc.sql("UPDATE roles SET name = :name, description = :description, updated_at = now() WHERE role_id = :id")
                .param("id", roleId)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .update();
    }

    /** Replaces the permissions of a role. */
    public void setPermissions(long roleId, Set<Permission> permissions) {
        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :id").param("id", roleId).update();
        for (Permission permission : permissions) {
            jdbc.sql("INSERT INTO role_permissions (role_id, permission) VALUES (:id, :p) ON CONFLICT DO NOTHING")
                    .param("id", roleId).param("p", permission.name()).update();
        }
    }

    public int delete(long roleId) {
        return jdbc.sql("DELETE FROM roles WHERE role_id = :id").param("id", roleId).update();
    }

    // ------------------------------------------------------------------ mapping

    private static RoleRow row(ResultSet rs) throws SQLException {
        return new RoleRow(rs.getLong("role_id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("system"), rs.getLong("user_count"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

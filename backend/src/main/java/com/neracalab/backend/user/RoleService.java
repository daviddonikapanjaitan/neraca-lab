package com.neracalab.backend.user;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.user.RoleRepository.RoleRow;
import com.neracalab.backend.user.UserDtos.PermissionView;
import com.neracalab.backend.user.UserDtos.RoleRequest;
import com.neracalab.backend.user.UserDtos.RoleView;
import com.neracalab.backend.web.ApiException;

/**
 * Role management. Rules beyond the request validation:
 * <ul>
 *   <li>role names are unique ignoring case; a role has at least one permission</li>
 *   <li>the built-in Administrator role keeps its name and all permissions and cannot be deleted
 *       (its description can be changed)</li>
 *   <li>a role assigned to users cannot be deleted (no user is left without a role by accident)</li>
 *   <li>administrators cannot remove the ADMIN permission that gives them their own admin access</li>
 * </ul>
 */
@Service
public class RoleService {

    private final RoleRepository roles;

    public RoleService(RoleRepository roles) {
        this.roles = roles;
    }

    public List<PermissionView> permissions() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionView(p, p.label(), p.description())).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return views(roles.list());
    }

    @Transactional(readOnly = true)
    public RoleView get(long roleId) {
        return views(List.of(row(roleId))).getFirst();
    }

    @Transactional
    public RoleView create(RoleRequest request) {
        String name = request.name().trim();
        if (roles.nameTaken(name, null)) {
            throw ApiException.conflict("A role named '" + name + "' already exists");
        }
        long roleId;
        try {
            roleId = roles.insert(name, UserService.blankToNull(request.description()), false);
        } catch (DuplicateKeyException e) {
            throw UserService.duplicate(e);
        }
        roles.setPermissions(roleId, EnumSet.copyOf(request.permissions()));
        return get(roleId);
    }

    @Transactional
    public RoleView update(long roleId, RoleRequest request, AuthenticatedUser actor) {
        RoleRow current = row(roleId);
        String name = request.name().trim();
        Set<Permission> permissions = EnumSet.copyOf(request.permissions());
        if (current.system()
                && (!name.equals(current.name()) || !permissions.equals(EnumSet.allOf(Permission.class)))) {
            throw ApiException.badRequest("The name and the permissions of the built-in " + current.name()
                    + " role cannot be changed (only its description)");
        }
        if (roles.nameTaken(name, roleId)) {
            throw ApiException.conflict("A role named '" + name + "' already exists");
        }
        Set<Long> actorRoles = roles.roleIdsOf(actor.userId());
        if (actorRoles.contains(roleId) && !permissions.contains(Permission.ADMIN)
                && !otherRolesGrantAdmin(actorRoles, roleId)) {
            throw ApiException.badRequest("You cannot remove the ADMIN permission from " + current.name()
                    + ": it is the role that gives you admin access");
        }
        try {
            roles.update(roleId, name, UserService.blankToNull(request.description()));
        } catch (DuplicateKeyException e) {
            throw UserService.duplicate(e);
        }
        roles.setPermissions(roleId, permissions);
        return get(roleId);
    }

    @Transactional
    public void delete(long roleId) {
        RoleRow current = row(roleId);
        if (current.system()) {
            throw ApiException.badRequest("The built-in " + current.name() + " role cannot be deleted");
        }
        if (current.userCount() > 0) {
            throw ApiException.conflict("The role '" + current.name() + "' is assigned to " + current.userCount()
                    + (current.userCount() == 1 ? " user" : " users") + ". Remove it from them first.");
        }
        roles.delete(roleId);
    }

    // ------------------------------------------------------------------ helpers

    private boolean otherRolesGrantAdmin(Set<Long> roleIds, long excludedRoleId) {
        Set<Long> others = new TreeSet<>(roleIds);
        others.remove(excludedRoleId);
        return roles.permissions(others).values().stream().anyMatch(p -> p.contains(Permission.ADMIN));
    }

    private RoleRow row(long roleId) {
        return roles.find(roleId).orElseThrow(() -> ApiException.notFound("Role not found", "No role " + roleId));
    }

    private List<RoleView> views(List<RoleRow> rows) {
        Map<Long, Set<Permission>> permissions = roles.permissions(rows.stream().map(RoleRow::roleId).toList());
        return rows.stream().map(r -> new RoleView(r.roleId(), r.name(), r.description(), r.system(),
                new TreeSet<>(permissions.getOrDefault(r.roleId(), Set.of())), r.userCount(), r.createdAt(),
                r.updatedAt())).toList();
    }
}

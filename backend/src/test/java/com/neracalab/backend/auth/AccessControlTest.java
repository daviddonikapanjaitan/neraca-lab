package com.neracalab.backend.auth;

import static com.neracalab.backend.auth.TestLogins.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Permission checks of every API group: users with one permission each, a user with several
 * roles, a user without roles, and a permission change that applies to a running session.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlTest {

    /** API -> permissions that allow it (empty = any logged-in user). */
    private static final Map<String, Set<Permission>> RULES = new LinkedHashMap<>();

    static {
        RULES.put("/api/v1/auth/me", Set.of());
        RULES.put("/api/v1/profile", Set.of());
        RULES.put("/api/v1/exchanges", Set.of());
        RULES.put("/api/v1/companies?exchange=IDX", Set.of(Permission.COMPANIES, Permission.INGESTION));
        RULES.put("/api/v1/companies/IDX/HRTA", Set.of(Permission.COMPANIES));
        RULES.put("/api/v1/ingestions", Set.of(Permission.INGESTION));
        RULES.put("/api/v1/prices/ingestions", Set.of(Permission.INGESTION));
        RULES.put("/api/v1/admin/users", Set.of(Permission.ADMIN));
        RULES.put("/api/v1/admin/roles", Set.of(Permission.ADMIN));
        RULES.put("/api/v1/admin/permissions", Set.of(Permission.ADMIN));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private TestAccounts accounts;

    @BeforeEach
    void accounts() {
        accounts = new TestAccounts(context);
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    @Test
    void eachPermissionOpensOnlyItsApis() throws Exception {
        for (Permission permission : Permission.values()) {
            long user = accounts.user(permission.name().toLowerCase(), accounts.role(permission.name().toLowerCase(), permission));
            assertAccess(accounts.token(user), Set.of(permission));
        }
    }

    @Test
    void permissionsOfSeveralRolesAddUp() throws Exception {
        long companies = accounts.role("companies", Permission.COMPANIES);
        long ingestion = accounts.role("ingestion", Permission.INGESTION);
        long user = accounts.user("both", companies, ingestion);
        assertAccess(accounts.token(user), Set.of(Permission.COMPANIES, Permission.INGESTION));
    }

    @Test
    void aUserWithoutRolesOnlyReachesTheOwnProfile() throws Exception {
        long user = accounts.user("none");
        assertAccess(accounts.token(user), Set.of());
    }

    @Test
    void aPermissionChangeAppliesToARunningSession() throws Exception {
        long role = accounts.role("changing", Permission.COMPANIES);
        String token = accounts.token(accounts.user("eve", role));
        mvc.perform(get("/api/v1/ingestions").with(bearer(token))).andExpect(status().isForbidden());

        jdbc.sql("INSERT INTO role_permissions (role_id, permission) VALUES (:r, 'INGESTION')").param("r", role).update();
        mvc.perform(get("/api/v1/ingestions").with(bearer(token))).andExpect(status().isOk());

        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :r AND permission = 'INGESTION'").param("r", role).update();
        mvc.perform(get("/api/v1/ingestions").with(bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("You need the INGESTION permission to use this API"));
    }

    private void assertAccess(String token, Set<Permission> held) throws Exception {
        for (Map.Entry<String, Set<Permission>> rule : RULES.entrySet()) {
            boolean allowed = rule.getValue().isEmpty() || rule.getValue().stream().anyMatch(held::contains);
            int status = mvc.perform(get(rule.getKey()).with(bearer(token))).andReturn().getResponse().getStatus();
            assertThat(status).as("%s with %s", rule.getKey(), held).isEqualTo(allowed ? 200 : 403);
        }
    }
}

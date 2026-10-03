package com.neracalab.backend.user;

import static com.neracalab.backend.auth.TestLogins.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;

import tools.jackson.databind.json.JsonMapper;

/** Role management API: CRUD, permissions, the built-in role and the delete / self-lockout rules. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRoleControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private TestAccounts accounts;
    private long adminRole;
    private String adminToken;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(context);
        adminRole = accounts.role("admins", Permission.ADMIN);
        adminToken = accounts.token(accounts.user("boss", adminRole));
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    @Test
    void listsThePermissions() throws Exception {
        mvc.perform(get("/api/v1/admin/permissions").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("ADMIN"))
                .andExpect(jsonPath("$[1].code").value("INGESTION"))
                .andExpect(jsonPath("$[2].code").value("COMPANIES"))
                .andExpect(jsonPath("$[0].description").isString());
    }

    @Test
    void createsReadsUpdatesAndDeletesARole() throws Exception {
        String name = accounts.name("Analysts");
        String body = send(post("/api/v1/admin/roles"), Map.of("name", " " + name + " ", "description", "Read companies",
                "permissions", List.of("COMPANIES", "INGESTION")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.userCount").value(0))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        accounts.trackRole(id);

        mvc.perform(get("/api/v1/admin/roles").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].system").value(true))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].description").value("Read companies"));

        send(put("/api/v1/admin/roles/" + id), Map.of("name", name + "-x", "permissions", List.of("COMPANIES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name + "-x"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.permissions[0]").value("COMPANIES"))
                .andExpect(jsonPath("$.permissions.length()").value(1));

        mvc.perform(delete("/api/v1/admin/roles/" + id).with(bearer(adminToken))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/roles/" + id).with(bearer(adminToken))).andExpect(status().isNotFound());
    }

    @Test
    void validatesNamePermissionsAndUniqueness() throws Exception {
        send(post("/api/v1/admin/roles"), Map.of("name", " ", "permissions", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.permissions").value("a role needs at least one permission"));
        send(post("/api/v1/admin/roles"), Map.of("name", accounts.name("x"), "permissions", List.of("SUPERUSER")))
                .andExpect(status().isBadRequest());
        send(post("/api/v1/admin/roles"), Map.of("name", accounts.name("ADMINS"), "permissions", List.of("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A role named '" + accounts.name("ADMINS") + "' already exists"));
        send(post("/api/v1/admin/roles"), Map.of("name", "administrator", "permissions", List.of("ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void theBuiltInRoleKeepsNameAndPermissionsAndCannotBeDeleted() throws Exception {
        long system = jdbc.sql("SELECT role_id FROM roles WHERE system").query(Long.class).single();
        String description = jdbc.sql("SELECT coalesce(description, '') FROM roles WHERE system").query(String.class).single();

        send(put("/api/v1/admin/roles/" + system), Map.of("name", "Root", "permissions", List.of("ADMIN", "INGESTION", "COMPANIES")))
                .andExpect(status().isBadRequest());
        send(put("/api/v1/admin/roles/" + system), Map.of("name", "Administrator", "permissions", List.of("ADMIN")))
                .andExpect(status().isBadRequest());
        send(put("/api/v1/admin/roles/" + system), Map.of("name", "Administrator", "description", "Everything",
                "permissions", List.of("COMPANIES", "ADMIN", "INGESTION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Everything"));
        mvc.perform(delete("/api/v1/admin/roles/" + system).with(bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The built-in Administrator role cannot be deleted"));

        jdbc.sql("UPDATE roles SET description = :d WHERE role_id = :id")
                .param("d", description.isEmpty() ? null : description).param("id", system).update();
    }

    @Test
    void anAssignedRoleCannotBeDeleted() throws Exception {
        long role = accounts.role("in-use", Permission.COMPANIES);
        accounts.user("member", role);
        mvc.perform(delete("/api/v1/admin/roles/" + role).with(bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The role '" + accounts.name("in-use")
                        + "' is assigned to 1 user. Remove it from them first."));
    }

    @Test
    void anAdministratorCannotRemoveTheirOwnAdminPermission() throws Exception {
        send(put("/api/v1/admin/roles/" + adminRole), Map.of("name", accounts.name("admins"), "permissions", List.of("COMPANIES")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("You cannot remove the ADMIN permission from " + accounts.name("admins")
                        + ": it is the role that gives you admin access"));
        // allowed when another role still gives admin access
        long second = accounts.role("admins-too", Permission.ADMIN);
        long boss = jdbc.sql("SELECT user_id FROM users WHERE username = :u").param("u", accounts.name("boss")).query(Long.class).single();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", boss).param("r", second).update();
        send(put("/api/v1/admin/roles/" + adminRole), Map.of("name", accounts.name("admins"), "permissions", List.of("COMPANIES")))
                .andExpect(status().isOk());
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Map<String, ?> body) throws Exception {
        return mvc.perform(request.with(bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }
}

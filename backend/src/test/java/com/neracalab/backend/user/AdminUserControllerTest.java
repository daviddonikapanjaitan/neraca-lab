package com.neracalab.backend.user;

import static com.neracalab.backend.auth.TestLogins.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.neracalab.backend.auth.TestLogins;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** User management API: CRUD, validation, uniqueness and the protections of root and self. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private TestAccounts accounts;
    /** an administrator created for the test (not root) */
    private long adminId;
    private String adminToken;
    private long viewerRole;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(context);
        long adminRole = accounts.role("admins", Permission.ADMIN);
        viewerRole = accounts.role("viewers", Permission.COMPANIES);
        adminId = accounts.user("boss", adminRole);
        adminToken = accounts.token(adminId);
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    @Test
    void createsReadsUpdatesAndDeletesAUser() throws Exception {
        String username = accounts.name("Frank");
        JsonNode created = body(send(post("/api/v1/admin/users"), Map.of(
                "username", username, "email", "  Frank." + username + "@Example.COM ", "password", "password-123",
                "fullName", "Frank Ocean", "phone", "+62 812 3456 7890", "dob", "1991-02-03",
                "address", "Jl. Merdeka 1", "roleIds", List.of(viewerRole)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/admin/users/"))));
        long id = created.get("id").asLong();
        accounts.track(id);
        assertThat(created.get("username").asString()).isEqualTo(username.toLowerCase());
        assertThat(created.get("email").asString()).isEqualTo(("frank." + username + "@example.com").toLowerCase());
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("permissions").toString()).isEqualTo("[\"COMPANIES\"]");

        // the new user can log in
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/users").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].fullName").value("Frank Ocean"));

        send(put("/api/v1/admin/users/" + id), Map.of("email", username + "@new.example", "fullName", "Frank O.",
                "phone", "", "address", "  ", "active", false, "roleIds", List.of(viewerRole)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value((username + "@new.example").toLowerCase()))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(delete("/api/v1/admin/users/" + id).with(bearer(adminToken))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/users/" + id).with(bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    @Test
    void usernameAndEmailAreUniqueIgnoringCase() throws Exception {
        long existing = accounts.user("taken", viewerRole);
        String existingName = accounts.name("taken");

        send(post("/api/v1/admin/users"), newUser(existingName.toUpperCase(), "other-" + existingName + "@x.io"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The username '" + existingName + "' is already taken"));
        send(post("/api/v1/admin/users"), newUser(accounts.name("fresh"), existingName.toUpperCase() + "@TEST.LOCAL"))
                .andExpect(status().isConflict());

        long other = accounts.user("other", viewerRole);
        send(put("/api/v1/admin/users/" + other), Map.of("email", existingName + "@test.local", "active", true,
                "roleIds", List.of(viewerRole)))
                .andExpect(status().isConflict());
        assertThat(existing).isPositive();
    }

    @Test
    void validatesTheRequest() throws Exception {
        send(post("/api/v1/admin/users"), Map.of("username", "a b", "email", "not-an-email", "password", "short",
                "phone", "abc", "dob", "2999-01-01", "roleIds", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.phone").exists())
                .andExpect(jsonPath("$.errors.dob").exists())
                .andExpect(jsonPath("$.errors.roleIds").value("a user needs at least one role"));
        send(post("/api/v1/admin/users"), Map.of("username", accounts.name("ghost"), "email", accounts.name("ghost") + "@x.io",
                "password", "password-123", "roleIds", List.of(-1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown role id(s): [-1]"));
        long user = accounts.user("rename", viewerRole);
        send(put("/api/v1/admin/users/" + user), Map.of("username", "renamed", "email", accounts.name("rename") + "@test.local",
                "active", true, "roleIds", List.of(viewerRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The username cannot be changed"));
    }

    @Test
    void protectsTheRootUserAndTheActingAdministrator() throws Exception {
        long rootId = jdbc.sql("SELECT user_id FROM users WHERE root").query(Long.class).single();
        long systemRole = jdbc.sql("SELECT role_id FROM roles WHERE system").query(Long.class).single();
        String rootEmail = jdbc.sql("SELECT email FROM users WHERE root").query(String.class).single();

        mvc.perform(delete("/api/v1/admin/users/" + rootId).with(bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The root user cannot be deleted"));
        send(put("/api/v1/admin/users/" + rootId), Map.of("email", rootEmail, "active", false, "roleIds", List.of(systemRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The root user cannot be deactivated"));
        send(put("/api/v1/admin/users/" + rootId), Map.of("email", rootEmail, "active", true, "roleIds", List.of(viewerRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The root user must keep the built-in Administrator role"));

        mvc.perform(delete("/api/v1/admin/users/" + adminId).with(bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("You cannot delete your own account"));
        String ownEmail = accounts.name("boss") + "@test.local";
        long ownRole = jdbc.sql("SELECT role_id FROM user_roles WHERE user_id = :u").param("u", adminId).query(Long.class).single();
        send(put("/api/v1/admin/users/" + adminId), Map.of("email", ownEmail, "active", false, "roleIds", List.of(ownRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("You cannot deactivate your own account"));
        send(put("/api/v1/admin/users/" + adminId), Map.of("email", ownEmail, "active", true, "roleIds", List.of(viewerRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("You cannot remove your own admin access: keep a role with the ADMIN permission"));
    }

    @Test
    void aNewPasswordOrADeactivationEndsTheUsersSessions() throws Exception {
        long user = accounts.user("rotate", viewerRole);
        String email = accounts.name("rotate") + "@test.local";
        String token = accounts.token(user);

        send(put("/api/v1/admin/users/" + user), Map.of("email", email, "active", true, "roleIds", List.of(viewerRole),
                "password", "another-password"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + accounts.name("rotate") + "\",\"password\":\"another-password\"}"))
                .andExpect(status().isOk());

        String second = accounts.token(user);
        send(put("/api/v1/admin/users/" + user), Map.of("email", email, "active", false, "roleIds", List.of(viewerRole)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").with(bearer(second))).andExpect(status().isUnauthorized());

        // changing your own password keeps your session
        String ownEmail = accounts.name("boss") + "@test.local";
        long ownRole = jdbc.sql("SELECT role_id FROM user_roles WHERE user_id = :u").param("u", adminId).query(Long.class).single();
        send(put("/api/v1/admin/users/" + adminId), Map.of("email", ownEmail, "active", true, "roleIds", List.of(ownRole),
                "password", "new-own-password"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").with(bearer(adminToken))).andExpect(status().isOk());
    }

    @Test
    void needsTheAdminPermission() throws Exception {
        String viewer = accounts.token(accounts.user("viewer", viewerRole));
        mvc.perform(get("/api/v1/admin/users").with(bearer(viewer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users").with(bearer(viewer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/users/" + adminId).with(bearer(viewer))).andExpect(status().isForbidden());
        TestLogins.logout(context, viewer);
    }

    private Map<String, Object> newUser(String username, String email) {
        return Map.of("username", username, "email", email, "password", "password-123", "roleIds", List.of(viewerRole));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Map<String, ?> body) throws Exception {
        return mvc.perform(request.with(bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}

package com.neracalab.backend.auth;

import static com.neracalab.backend.auth.TestLogins.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Login, logout, me and the 401 answers, against the Docker Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

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
    void logsInWithUsernameIgnoringCaseAndReturnsTokenAndUser() throws Exception {
        long role = accounts.role("analyst", Permission.COMPANIES);
        accounts.user("anna", role);

        String body = mvc.perform(login("  " + accounts.name("anna").toUpperCase() + " ", TestAccounts.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.username").value(accounts.name("anna")))
                .andExpect(jsonPath("$.user.permissions[0]").value("COMPANIES"))
                .andExpect(jsonPath("$.user.roles[0].name").value(accounts.name("analyst")))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(body);
        String token = response.get("token").asString();
        Instant expiresAt = Instant.parse(response.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(Instant.now().plus(Duration.ofHours(11)), Instant.now().plus(Duration.ofHours(13)));
        assertThat(response.get("user").has("passwordHash")).isFalse();
        assertThat(body).doesNotContain("$2a$");

        mvc.perform(get("/api/v1/auth/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(accounts.name("anna")));
        // only the hash of the token is stored
        assertThat(jdbc.sql("SELECT count(*) FROM user_sessions WHERE token_hash = :t").param("t", token)
                .query(Long.class).single()).isZero();
    }

    @Test
    void rejectsWrongPasswordUnknownUserAndDeactivatedUser() throws Exception {
        long role = accounts.role("analyst", Permission.COMPANIES);
        long userId = accounts.user("bob", role);

        mvc.perform(login(accounts.name("bob"), "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Login failed"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
        mvc.perform(login(accounts.name("nobody"), TestAccounts.PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());

        String token = accounts.token(userId);
        jdbc.sql("UPDATE users SET active = FALSE WHERE user_id = :id").param("id", userId).update();
        mvc.perform(login(accounts.name("bob"), TestAccounts.PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("This account is deactivated. Contact an administrator."));
        mvc.perform(get("/api/v1/auth/me").with(bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        long userId = accounts.user("carol", accounts.role("analyst", Permission.COMPANIES));
        String token = accounts.token(userId);

        mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").with(bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().isUnauthorized());
        // logging out again (expired / unknown token) still succeeds
        mvc.perform(post("/api/v1/auth/logout").with(bearer(token))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void expiredSessionIsRejected() throws Exception {
        long userId = accounts.user("dave", accounts.role("analyst", Permission.COMPANIES));
        String token = accounts.token(userId);
        jdbc.sql("UPDATE user_sessions SET created_at = now() - interval '2 days', expires_at = now() - interval '1 second' WHERE user_id = :id")
                .param("id", userId).update();

        mvc.perform(get("/api/v1/auth/me").with(bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Not authenticated"));
    }

    @Test
    void everyApiNeedsALoginExceptLoginLogoutAndHealth() throws Exception {
        for (String path : new String[] {"/api/v1/auth/me", "/api/v1/exchanges", "/api/v1/companies",
                "/api/v1/companies/IDX/HRTA", "/api/v1/ingestions", "/api/v1/prices/ingestions",
                "/api/v1/admin/users", "/api/v1/admin/roles", "/api/v1/admin/permissions", "/api/v1/profile",
                "/api/v1/profile/avatar"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.title").value("Not authenticated"));
            mvc.perform(get(path).header("Authorization", "Bearer not-a-valid-token")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Basic YWRtaW46YWRtaW4=")).andExpect(status().isUnauthorized());
        }
        mvc.perform(multipart("/api/v1/financial-statements/upload")
                        .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void rootUserExistsWithTheAdministratorRoleAndEveryPermission() throws Exception {
        String token = TestLogins.rootToken(context);
        try {
            mvc.perform(get("/api/v1/auth/me").with(bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("admin"))
                    .andExpect(jsonPath("$.root").value(true))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.email").isString())
                    .andExpect(jsonPath("$.roles[?(@.name == 'Administrator' && @.system == true)]").exists())
                    .andExpect(jsonPath("$.permissions.length()").value(3));
        } finally {
            TestLogins.logout(context, token);
        }
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String username, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }
}

package com.neracalab.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Sessions for API tests: every API except login and health needs a bearer token. */
public final class TestLogins {

    private TestLogins() {
    }

    /** A new session of the root user (every permission), without knowing its password. */
    public static String rootToken(ApplicationContext context) {
        long rootId = context.getBean(JdbcClient.class).sql("SELECT user_id FROM users WHERE root")
                .query(Long.class).single();
        return context.getBean(AuthService.class).createSession(rootId).token();
    }

    /** A new session of a user. */
    public static String token(ApplicationContext context, long userId) {
        return context.getBean(AuthService.class).createSession(userId).token();
    }

    /** MockMvc that sends {@code Authorization: Bearer <token>} with every request. */
    public static MockMvc mockMvc(WebApplicationContext context, String token) {
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .build();
    }

    /** Adds the bearer token to one request. */
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    public static void logout(ApplicationContext context, String token) {
        if (token != null) {
            context.getBean(AuthService.class).logout(token);
        }
    }
}

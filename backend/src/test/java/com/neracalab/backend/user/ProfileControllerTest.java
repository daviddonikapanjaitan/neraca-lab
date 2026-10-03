package com.neracalab.backend.user;

import static com.neracalab.backend.auth.TestLogins.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;

/** Own profile: address / phone / date of birth and avatar only; username and email are fixed. */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest {

    /** Smallest valid PNG header plus some bytes (the type is detected from the magic bytes). */
    private static final byte[] PNG = concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, new byte[64]);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private TestAccounts accounts;
    private long userId;
    private String token;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(context);
        userId = accounts.user("pat", accounts.role("viewers", Permission.COMPANIES));
        token = accounts.token(userId);
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    @Test
    void updatesAddressPhoneAndDateOfBirth() throws Exception {
        mvc.perform(put("/api/v1/profile").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\" Jl. Thamrin 9 \",\"phone\":\"+62 811 000 111\",\"dob\":\"1995-05-17\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Jl. Thamrin 9"))
                .andExpect(jsonPath("$.phone").value("+62 811 000 111"))
                .andExpect(jsonPath("$.dob").value("1995-05-17"))
                .andExpect(jsonPath("$.username").value(accounts.name("pat")));

        // sending the unchanged username / email is fine; clearing fields stores NULL
        mvc.perform(put("/api/v1/profile").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"\",\"phone\":\"\",\"dob\":null,\"username\":\"" + accounts.name("pat")
                                + "\",\"email\":\"" + accounts.name("pat") + "@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.dob").doesNotExist());
    }

    @Test
    void usernameAndEmailCannotBeChanged() throws Exception {
        mvc.perform(put("/api/v1/profile").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone-else@test.local\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The email cannot be changed"));
        mvc.perform(put("/api/v1/profile").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone-else\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The username cannot be changed"));
        mvc.perform(put("/api/v1/profile").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"call me\",\"dob\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists())
                .andExpect(jsonPath("$.errors.dob").exists());

        assertThat(jdbc.sql("SELECT username || ' ' || email FROM users WHERE user_id = :id").param("id", userId)
                .query(String.class).single()).isEqualTo(accounts.name("pat") + " " + accounts.name("pat") + "@test.local");
    }

    @Test
    void uploadsShowsAndRemovesTheAvatar() throws Exception {
        mvc.perform(get("/api/v1/profile/avatar").with(bearer(token))).andExpect(status().isNotFound());

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/profile/avatar").file(new MockMultipartFile("file", "me.png", "image/png", PNG))
                        .with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true))
                .andExpect(jsonPath("$.avatarUpdatedAt").isString());
        byte[] stored = mvc.perform(get("/api/v1/profile/avatar").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(stored).isEqualTo(PNG);

        // an administrator sees it too
        String admin = accounts.token(accounts.user("boss", accounts.role("admins", Permission.ADMIN)));
        mvc.perform(get("/api/v1/admin/users/" + userId + "/avatar").with(bearer(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users/" + userId + "/avatar").with(bearer(token))).andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/profile/avatar").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(false));
        mvc.perform(get("/api/v1/profile/avatar").with(bearer(token))).andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonImagesSvgAndTooLargePictures() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/profile/avatar").file(new MockMultipartFile("file", "x.svg", "image/svg+xml", svg))
                        .with(bearer(token)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value("Only PNG, JPEG, WebP and GIF pictures are accepted"));
        // a PNG name / content type does not help: the content decides
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png", svg))
                        .with(bearer(token)))
                .andExpect(status().isUnsupportedMediaType());
        byte[] large = concat(PNG, new byte[AvatarImages.MAX_BYTES]);
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/profile/avatar").file(new MockMultipartFile("file", "big.png", "image/png", large))
                        .with(bearer(token)))
                .andExpect(status().isContentTooLarge());
        assertThat(jdbc.sql("SELECT avatar IS NULL FROM users WHERE user_id = :id").param("id", userId)
                .query(Boolean.class).single()).isTrue();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}

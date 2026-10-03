package com.neracalab.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.neracalab.backend.user.UserRepository;
import com.neracalab.backend.user.UserRepository.Credentials;

/**
 * Login, session tokens and logout. A token is 32 random bytes (base64url); only its SHA-256 is
 * stored, so the database never holds a usable token.
 */
@Service
public class AuthService {

    /** A new session: the bearer token (returned once) and its expiry. */
    public record Session(String token, Instant expiresAt) {
    }

    /** A successful login. */
    public record Login(long userId, Session session) {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder encoder;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the username is unknown, so both cases take about the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordEncoder encoder,
                       AuthProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.properties = properties;
        this.dummyHash = encoder.encode("dummy-password-for-unknown-users");
    }

    /**
     * Checks username (case-insensitive) and password and opens a session.
     *
     * @throws com.neracalab.backend.web.ApiException 401 for a wrong username / password or a deactivated user
     */
    public Login login(String username, String password) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        Optional<Credentials> credentials = normalized.isEmpty() ? Optional.empty() : users.credentials(normalized);
        boolean matches = encoder.matches(password == null ? "" : password,
                credentials.map(Credentials::passwordHash).orElse(dummyHash));
        if (credentials.isEmpty() || !matches) {
            throw AuthExceptions.badCredentials();
        }
        if (!credentials.get().active()) {
            throw AuthExceptions.deactivated();
        }
        sessions.deleteExpired();
        return new Login(credentials.get().userId(), createSession(credentials.get().userId()));
    }

    /** Opens a session for a user (login; tests). */
    public Session createSession(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(properties.sessionTtl());
        sessions.insert(hash(token), userId, expiresAt);
        return new Session(token, expiresAt);
    }

    /** The user of a valid session token; empty for a missing, unknown or expired token or a deactivated user. */
    public Optional<AuthenticatedUser> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        return sessions.findUser(hash(token));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.delete(hash(token));
        }
    }

    /** Ends every session of a user except the one of {@code keepToken} (null = all). */
    public void endSessions(long userId, String keepToken) {
        sessions.deleteForUser(userId, keepToken == null ? null : hash(keepToken));
    }

    public String hashPassword(String password) {
        return encoder.encode(password);
    }

    /** Lower-case hex SHA-256, as stored in {@code user_sessions.token_hash}. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

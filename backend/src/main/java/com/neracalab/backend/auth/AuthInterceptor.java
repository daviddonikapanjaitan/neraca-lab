package com.neracalab.backend.auth;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Access check of every {@code /api/**} request, BEFORE the controller runs. Each endpoint declares
 * its rule with exactly one of {@link PublicAccess}, {@link RequiresLogin} or
 * {@link RequiresPermission} (on the method, else on the controller class). Deny by default: an
 * endpoint without a rule is refused (403) and logged as a programming error.
 * <ul>
 *   <li>no / unknown / expired token, deactivated user: 401</li>
 *   <li>logged in without one of the required permissions: 403</li>
 * </ul>
 * The user is resolved from {@code Authorization: Bearer <token>} and stored in the request for
 * controller parameters of type {@link AuthenticatedUser}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** Request attribute holding the {@link AuthenticatedUser}. */
    public static final String USER_ATTRIBUTE = AuthInterceptor.class.getName() + ".user";
    /** Request attribute holding the bearer token of the request. */
    public static final String TOKEN_ATTRIBUTE = AuthInterceptor.class.getName() + ".token";

    /** The access rule of an endpoint. */
    public sealed interface Rule {

        record Public() implements Rule {
        }

        record Login() implements Rule {
        }

        record AnyOf(Permission[] permissions) implements Rule {
        }
    }

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;   // not a controller endpoint (e.g. a 404 of an unknown path)
        }
        Rule rule = rule(method);
        if (rule == null) {
            log.error("{} has no access rule (@PublicAccess, @RequiresLogin or @RequiresPermission); request refused",
                    method.getShortLogMessage());
            throw AuthExceptions.forbidden("This endpoint has no access rule");
        }
        if (rule instanceof Rule.Public) {
            return true;
        }
        String token = bearerToken(request);
        AuthenticatedUser user = auth.authenticate(token).orElseThrow(AuthExceptions::notAuthenticated);
        request.setAttribute(USER_ATTRIBUTE, user);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        if (rule instanceof Rule.AnyOf(Permission[] permissions)
                && Arrays.stream(permissions).noneMatch(user::has)) {
            throw AuthExceptions.forbidden("You need the " + names(permissions) + " permission to use this API");
        }
        return true;
    }

    /** The rule on the method, else the one on its class; null when neither declares one. */
    public static Rule rule(HandlerMethod method) {
        Rule rule = rule(method.getMethod());
        return rule != null ? rule : rule(method.getBeanType());
    }

    private static Rule rule(AnnotatedElement element) {
        if (AnnotatedElementUtils.hasAnnotation(element, PublicAccess.class)) {
            return new Rule.Public();
        }
        if (AnnotatedElementUtils.hasAnnotation(element, RequiresLogin.class)) {
            return new Rule.Login();
        }
        RequiresPermission permission = AnnotatedElementUtils.findMergedAnnotation(element, RequiresPermission.class);
        return permission == null ? null : new Rule.AnyOf(permission.value());
    }

    /** The token of {@code Authorization: Bearer <token>}; null without one. */
    static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.length() < 7 || !value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** "ADMIN" or "INGESTION or COMPANIES" */
    private static String names(Permission[] permissions) {
        return Arrays.stream(permissions).map(Enum::name).collect(Collectors.joining(" or "));
    }
}

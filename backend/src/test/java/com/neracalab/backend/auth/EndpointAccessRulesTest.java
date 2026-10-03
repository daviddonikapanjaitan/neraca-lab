package com.neracalab.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Every API endpoint declares an access rule, and only login, logout and health are public. */
@SpringBootTest
class EndpointAccessRulesTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void everyApiEndpointHasAnAccessRule() {
        List<String> missing = new ArrayList<>();
        TreeSet<String> publicEndpoints = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            for (String pattern : patterns(info)) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                AuthInterceptor.Rule rule = AuthInterceptor.rule(method);
                if (rule == null) {
                    missing.add(pattern + " " + name(method));
                } else if (rule instanceof AuthInterceptor.Rule.Public) {
                    publicEndpoints.add(pattern);
                }
            }
        });
        assertThat(missing).as("API endpoints without @PublicAccess / @RequiresLogin / @RequiresPermission").isEmpty();
        assertThat(publicEndpoints).containsExactly("/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/health");
    }

    private static List<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? List.of()
                : info.getPathPatternsCondition().getPatternValues().stream().toList();
    }

    private static String name(HandlerMethod method) {
        return method.getBeanType().getSimpleName() + "." + method.getMethod().getName();
    }
}

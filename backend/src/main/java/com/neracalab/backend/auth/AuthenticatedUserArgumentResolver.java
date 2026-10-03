package com.neracalab.backend.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Controller parameters of type {@link AuthenticatedUser}: the user resolved by {@link AuthInterceptor}. */
class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthenticatedUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Object user = request.getAttribute(AuthInterceptor.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user == null) {
            throw AuthExceptions.notAuthenticated();
        }
        return user;
    }
}

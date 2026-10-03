package com.neracalab.backend.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Access rule of an API endpoint: a logged-in user holding at least ONE of the listed permissions.
 * On a controller class it applies to every endpoint; an access annotation on a method replaces
 * the one of the class. See {@link AuthInterceptor}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    Permission[] value();
}

package com.sentinel.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Marks a test that needs a container runtime, and skips it when none is reachable.
 *
 * <p>{@code @Inherited} matters here: without it the condition would apply only to the base class
 * that declares it, and every subclass would start a container regardless.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@EnabledIf(
        value = "com.sentinel.testsupport.DockerEnvironment#isAvailable",
        disabledReason = "no Docker daemon reachable; integration tests skipped")
public @interface RequiresDocker {
}

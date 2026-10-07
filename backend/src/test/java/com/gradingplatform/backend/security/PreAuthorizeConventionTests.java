package com.gradingplatform.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * 2.5a: the role convention is written as strings inside `@PreAuthorize`, and a typo such as
 * `hasRole('TEACHR')` compiles and simply locks everybody out. This reads the real controllers and
 * checks every role name is a {@link Role}, and that the annotation sits on methods, not classes.
 */
class PreAuthorizeConventionTests {

    private static final Pattern ROLE_CALL = Pattern.compile("has(?:Any)?Role\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    @Test
    void everyRoleNamedInAControllerRuleIsARealRole() {
        List<String> unknown = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
                if (rule == null) {
                    continue;
                }
                for (String name : roleNames(rule.value())) {
                    if (Arrays.stream(Role.values())
                            .noneMatch(role -> role.name().equals(name))) {
                        unknown.add(controller.getSimpleName() + "." + method.getName() + ": " + name);
                    }
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    @Test
    void theRuleIsOnTheMethodNeverTheClass() {
        // One look at a method shows who may call it; a class-level rule would be easy to miss.
        List<String> offenders = controllers().stream()
                .filter(c -> AnnotatedElementUtils.hasAnnotation(c, PreAuthorize.class))
                .map(Class::getSimpleName)
                .toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    void roleNamesAreReadOutOfAnExpression() {
        assertThat(roleNames("hasRole('ADMIN')")).containsExactly("ADMIN");
        assertThat(roleNames("hasAnyRole('TEACHER', 'ADMIN')")).containsExactly("TEACHER", "ADMIN");
        assertThat(roleNames("hasRole('TEACHR')")).containsExactly("TEACHR");
        assertThat(roleNames("isAuthenticated()")).isEmpty();
    }

    private static List<String> roleNames(String expression) {
        List<String> names = new ArrayList<>();
        Matcher call = ROLE_CALL.matcher(expression);
        while (call.find()) {
            Matcher quoted = QUOTED.matcher(call.group(1));
            while (quoted.find()) {
                names.add(quoted.group(1));
            }
        }
        return names;
    }

    private static List<Class<?>> controllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> classes = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.gradingplatform.backend.controller")) {
            try {
                classes.add(Class.forName(((AnnotatedBeanDefinition) definition).getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }
}

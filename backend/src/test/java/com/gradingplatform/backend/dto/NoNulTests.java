package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.CriterionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.6a: {@link NoNul}, and the guard that every text field a request body can carry has it. The
 * guard reads the controllers themselves (every {@code @RequestBody} parameter of every
 * {@code @RestController}), so a new endpoint or a new field is checked without being listed here.
 * The end-to-end check, one row per field over real HTTP, is {@code RealHttpNulTextTests}. 7.7i: the
 * guard walks into nested records and list or array elements, reports what it cannot check, and
 * requires {@code @Valid} on every {@code @RequestBody} (without it no constraint runs at all).
 */
class NoNulTests {

    /** Text fields with a rule of their own, each with the reason. */
    private static final Set<String> EXEMPT = Set.of(
            // Refused as SOURCE_NOT_TEXT by SubmissionService (5.1c), with unpaired surrogates too.
            "SubmissionRequest.sourceCode",
            // Never stored or sent to the database: only hashed with BCrypt.
            "SignupRequest.password",
            "SigninRequest.password");

    private final NoNul.Validator validator = new NoNul.Validator();

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "two\nlines\tand a tab", "\u0001\u001f", "ă ș ț", "😀"})
    void textWithoutANulIsValid(String text) {
        assertThat(validator.isValid(text, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "a\u0000b", "end\u0000", "\u0000start"})
    void anyNulIsRefused(String text) {
        assertThat(validator.isValid(text, null)).isFalse();
    }

    @Test
    void nullIsLeftToTheOtherConstraints() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void beanValidationReportsTheFieldWithTheFixedMessage() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator beans = factory.getValidator();

            Set<ConstraintViolation<RubricCriterionRequest>> violations =
                    beans.validate(new RubricCriterionRequest("Te\u0000sts", CriterionType.TESTS, 10));

            assertThat(violations).singleElement().satisfies(v -> {
                assertThat(v.getPropertyPath()).hasToString("name");
                assertThat(v.getMessage()).isEqualTo("must not contain a NUL character");
            });
            assertThat(beans.validate(new ManualScoreRequest(BigDecimal.ONE, "fine")))
                    .isEmpty();
        }
    }

    @Test
    void everyTextFieldOfEveryRequestBodyCarriesTheConstraint() throws Exception {
        List<String> missing = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Class<?> body : requestBodyTypes()) {
            Walk walk = walk(body);
            walk.textFields.forEach((field, checked) -> {
                if (!checked && !EXEMPT.contains(field)) {
                    missing.add(field);
                }
            });
            problems.addAll(walk.problems);
        }

        assertThat(missing).as("text fields of a request body without @NoNul").isEmpty();
        assertThat(problems)
                .as("what a request body carries that the guard cannot check")
                .isEmpty();
    }

    @Test
    void everyRequestBodyIsValidated() throws Exception {
        // 7.7i: without @Valid on the parameter, Spring never runs @NoNul (or any other constraint).
        List<String> unvalidated = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            unvalidated.addAll(requestBodiesWithoutValid(controller));
        }

        assertThat(unvalidated).as("@RequestBody parameters without @Valid").isEmpty();
    }

    @Test
    void theGuardSeesTheBodiesAndTheExemptionsAreReal() throws Exception {
        Set<String> fields = new TreeSet<>();
        for (Class<?> body : requestBodyTypes()) {
            fields.addAll(walk(body).textFields.keySet());
        }

        assertThat(fields)
                .as("the guard must find the request bodies, or it passes vacuously")
                .hasSizeGreaterThanOrEqualTo(19)
                .contains("CourseRequest.title", "TestCaseRequest.input", "HintReportRequest.reason")
                .containsAll(EXEMPT);
    }

    // ---- 7.7i: the walk itself, on shapes no request body has yet ----

    record Inner(@NoNul String checked, String bare) {}

    record Outer(
            String top,
            @Valid Inner inner,
            Inner notCascaded,
            @Valid List<Inner> inners,
            List<@NoNul String> tags,
            List<String> bareTags,
            String[] lines,
            Map<String, String> labels,
            Object anything,
            Character letter,
            int count,
            BigDecimal score,
            Boolean flag,
            Instant when,
            CriterionType type) {}

    record Node(@NoNul String name, @Valid List<Node> children) {}

    @Test
    void theWalkFindsTextInNestedRecordsAndInListAndArrayElements() {
        Walk walk = walk(Outer.class);

        assertThat(walk.textFields)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "Outer.top", false,
                        "Outer.inner.checked", true,
                        "Outer.inner.bare", false,
                        "Outer.notCascaded.checked", true,
                        "Outer.notCascaded.bare", false,
                        "Outer.inners[].checked", true,
                        "Outer.inners[].bare", false,
                        "Outer.tags[]", true,
                        "Outer.bareTags[]", false,
                        "Outer.lines[]", false));
    }

    @Test
    void theWalkReportsANestedRecordWithoutValidAndEveryTypeItDoesNotKnow() {
        assertThat(walk(Outer.class).problems)
                .containsExactlyInAnyOrder(
                        "Outer.notCascaded: a nested record needs @Valid, or its fields are never validated",
                        "Outer.labels: java.util.Map<java.lang.String, java.lang.String> is not a type the guard"
                                + " knows; teach it",
                        "Outer.anything: java.lang.Object is not a type the guard knows; teach it",
                        "Outer.letter: java.lang.Character is not a type the guard knows; teach it");
    }

    @Test
    void aBodyThatIsNotARecordIsReported() {
        assertThat(walk(String.class).problems).containsExactly("String: a request body that is not a record");
    }

    @Test
    void aRecordThatContainsItselfIsWalkedOnce() {
        Walk walk = walk(Node.class);

        assertThat(walk.textFields).containsExactly(Map.entry("Node.name", true));
        assertThat(walk.problems).isEmpty();
    }

    @Test
    void beanValidationRefusesANulInAListElementAndInANestedRecord() {
        // The walk's demands are the ones that work: List<@NoNul String> and a @Valid nested record.
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator beans = factory.getValidator();

            assertThat(beans.validate(outer(new Inner("ok", "x"), List.of("a", "b"))))
                    .isEmpty();
            assertThat(beans.validate(outer(new Inner("o\u0000k", "x"), List.of("a"))))
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("inner.checked", "inners[0].checked");
            assertThat(beans.validate(outer(new Inner("ok", "x"), List.of("a", "b\u0000"))))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("tags[1].<list element>");
        }
    }

    @Test
    void aMissingValidOnARequestBodyIsReported() {
        assertThat(requestBodiesWithoutValid(Endpoints.class)).containsExactly("Endpoints.unvalidated");
    }

    /** Not a {@code @RestController}: only {@link #requestBodiesWithoutValid} looks at it. */
    static class Endpoints {

        void validated(@Valid @RequestBody CourseRequest request) {}

        void alsoValidated(@Validated @RequestBody CourseRequest request) {}

        void unvalidated(@RequestBody CourseRequest request) {}

        void notABody(CourseRequest request) {}
    }

    private static Outer outer(Inner inner, List<String> tags) {
        return new Outer(
                "t",
                inner,
                inner,
                List.of(inner),
                tags,
                List.of(),
                new String[0],
                Map.of(),
                null,
                null,
                1,
                null,
                null,
                null,
                null);
    }

    // ---- the walk ----

    /**
     * What one request body can carry: each text field ("Body.path", to whether it has {@code @NoNul}),
     * and what cannot be checked.
     */
    static final class Walk {
        final Map<String, Boolean> textFields = new TreeMap<>();
        final List<String> problems = new ArrayList<>();
    }

    static Walk walk(Class<?> body) {
        Walk walk = new Walk();
        if (!body.isRecord()) {
            walk.problems.add(body.getSimpleName() + ": a request body that is not a record");
        } else {
            components(body, body.getSimpleName(), walk, new HashSet<>());
        }
        return walk;
    }

    private static void components(Class<?> record, String path, Walk walk, Set<Class<?>> walking) {
        if (!walking.add(record)) {
            return; // a record that contains itself: its fields are already being walked
        }
        for (RecordComponent component : record.getRecordComponents()) {
            value(
                    component.getAnnotatedType(),
                    path + "." + component.getName(),
                    component.isAnnotationPresent(NoNul.class),
                    component.isAnnotationPresent(Valid.class),
                    walk,
                    walking);
        }
        walking.remove(record);
    }

    /**
     * One value at {@code path}: text is recorded, a record is walked into (it needs {@code @Valid}), a
     * list's or array's element is walked as {@code path[]} (cascaded by an {@code @Valid} on the list),
     * values that cannot hold text are skipped, and anything else is reported.
     */
    private static void value(
            AnnotatedType type, String path, boolean noNul, boolean valid, Walk walk, Set<Class<?>> walking) {
        Class<?> raw = raw(type.getType());
        // @Valid cannot target a record component, so Java puts it on the component's type (TYPE_USE).
        valid = valid || type.isAnnotationPresent(Valid.class);
        if (raw == String.class) {
            walk.textFields.put(path, noNul || type.isAnnotationPresent(NoNul.class));
        } else if (raw.isRecord()) {
            if (!valid) {
                walk.problems.add(path + ": a nested record needs @Valid, or its fields are never validated");
            }
            components(raw, path, walk, walking);
        } else if (Collection.class.isAssignableFrom(raw) && type instanceof AnnotatedParameterizedType list) {
            value(list.getAnnotatedActualTypeArguments()[0], path + "[]", false, valid, walk, walking);
        } else if (type instanceof AnnotatedArrayType array
                && !raw.getComponentType().isPrimitive()) {
            value(array.getAnnotatedGenericComponentType(), path + "[]", false, valid, walk, walking);
        } else if (!holdsNoText(raw)) {
            walk.problems.add(path + ": " + type.getType().getTypeName() + " is not a type the guard knows; teach it");
        }
    }

    private static boolean holdsNoText(Class<?> type) {
        return (type.isPrimitive() && type != char.class)
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type.isEnum()
                || type.getPackageName().equals("java.time");
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> plain) {
            return plain;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        return Object.class; // a wildcard or a type variable: reported as unknown
    }

    // ---- the controllers ----

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.gradingplatform.backend")) {
            controllers.add(Class.forName(candidate.getBeanClassName()));
        }
        return controllers;
    }

    /** The type of every {@code @RequestBody} parameter of every {@code @RestController}. */
    private static Set<Class<?>> requestBodyTypes() throws ClassNotFoundException {
        Set<Class<?>> bodies = new HashSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)) {
                        bodies.add(parameter.getType());
                    }
                }
            }
        }
        return bodies;
    }

    /** "Controller.method" for each {@code @RequestBody} parameter without {@code @Valid} or {@code @Validated}. */
    private static List<String> requestBodiesWithoutValid(Class<?> controller) {
        List<String> unvalidated = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class)
                        && !parameter.isAnnotationPresent(Valid.class)
                        && !parameter.isAnnotationPresent(Validated.class)) {
                    unvalidated.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        return unvalidated;
    }
}

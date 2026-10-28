package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.CriterionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.6a: {@link NoNul}, and the guard that every text field a request body can carry has it. The
 * guard reads the controllers themselves (every {@code @RequestBody} parameter of every
 * {@code @RestController}), so a new endpoint or a new field is checked without being listed here.
 * The end-to-end check, one row per field over real HTTP, is {@code RealHttpNulTextTests}.
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
        for (String field : requestBodyTextFields()) {
            if (!EXEMPT.contains(field) && !hasNoNul(field)) {
                missing.add(field);
            }
        }

        assertThat(missing).as("text fields of a request body without @NoNul").isEmpty();
    }

    @Test
    void theGuardSeesTheBodiesAndTheExemptionsAreReal() throws Exception {
        Set<String> fields = requestBodyTextFields();

        assertThat(fields)
                .as("the guard must find the request bodies, or it passes vacuously")
                .hasSizeGreaterThanOrEqualTo(19)
                .contains("CourseRequest.title", "TestCaseRequest.input", "HintReportRequest.reason")
                .containsAll(EXEMPT);
    }

    /** "Record.component" for every String component of every {@code @RequestBody} record of every controller. */
    private static Set<String> requestBodyTextFields() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> fields = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.gradingplatform.backend")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && parameter.getType().isRecord()) {
                        for (RecordComponent component : parameter.getType().getRecordComponents()) {
                            if (component.getType() == String.class) {
                                fields.add(parameter.getType().getSimpleName() + "." + component.getName());
                            }
                        }
                    }
                }
            }
        }
        return fields;
    }

    private static boolean hasNoNul(String field) throws ClassNotFoundException {
        String[] parts = field.split("\\.");
        Class<?> type = Class.forName(NoNulTests.class.getPackageName() + "." + parts[0]);
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(parts[1])) {
                return component.isAnnotationPresent(NoNul.class);
            }
        }
        throw new AssertionError("no component " + field);
    }
}

package com.gradingplatform.backend.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string must not contain a NUL character, U+0000 (null is valid). Postgres {@code
 * TEXT} refuses it, so without this check a NUL sent in JSON as {@code \u0000} reached the database
 * and came back as a 500 (7.6a). Every text field of a request record carries it, except the
 * submitted source code, which has its own rule ({@code SOURCE_NOT_TEXT}, 5.1c), and passwords,
 * which are only hashed; {@code NoNulTests} fails on a new text field without it.
 */
@Documented
@Constraint(validatedBy = NoNul.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoNul {

    String message() default "must not contain a NUL character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NoNul, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.indexOf('\u0000') < 0;
        }
    }
}

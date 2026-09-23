package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.TaskPriority;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The request field must carry one of {@link TaskPriority}.
 *
 * <p>The twin of {@link InTaskStatusVocabulary}, and a twin on purpose rather than
 * a generic "in enum" constraint: the reasoning that keeps the field a
 * {@code String} -- an enum-typed field turns a wrong value into a
 * {@code malformed-request} that names no field and says the body could not be
 * read, which is false -- is written there once and applies here unchanged
 * (ADR-011 §4). Null and blank pass, so that {@code @NotBlank} owns "required".
 */
@Documented
@Constraint(validatedBy = InTaskPriorityVocabulary.Validator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface InTaskPriorityVocabulary {

    String message() default "priority must be one of {vocabulary}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<InTaskPriorityVocabulary, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {

            if (value == null || value.isBlank() || TaskPriority.contains(value)) {
                return true;
            }

            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            context.getDefaultConstraintMessageTemplate()
                                    .replace("{vocabulary}", TaskPriority.vocabulary()))
                    .addConstraintViolation();
            return false;
        }
    }
}

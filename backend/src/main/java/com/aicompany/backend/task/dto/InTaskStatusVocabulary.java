package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.TaskStatus;
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
 * The request field must carry one of {@link TaskStatus}.
 *
 * <h2>Why this exists instead of typing the field as the enum</h2>
 *
 * <p>Declaring {@code TaskStatus status} in the request record looks like the
 * clean answer and produces the wrong contract. Jackson would fail to
 * deserialise, Spring would raise {@code HttpMessageNotReadableException}, and
 * the advice would answer {@code urn:ai-company-os:problem:malformed-request}
 * with the detail "The request body could not be read".
 *
 * <p>That statement is <strong>false</strong> -- the body was read perfectly --
 * and ADR-007 §2 made {@code type} the machine-readable half of the contract
 * precisely because clients branch on it: one that receives
 * {@code malformed-request} goes looking for a defect in its own serialiser.
 * Worse, that branch never populates {@code errors}, so the offending field
 * would vanish, while a <em>blank</em> status already answers
 * {@code validation-failed} with {@code errors.status}. The same field would
 * report two nearby mistakes in two different shapes, and give the worse one to
 * the likelier mistake.
 *
 * <p>So the field stays a {@code String} and this constraint runs on it. An
 * unknown value is a validation failure that names the field -- same
 * {@code type}, same shape, no new {@link
 * com.aicompany.backend.api.ApiProblem} member, and {@code ApiExceptionHandler}
 * untouched. ADR-011 §4.
 *
 * <p>Null passes, so that {@code @NotBlank} owns the "required" message and this
 * one owns "not one of ours". Two constraints reporting the same absence would
 * put two sentences under one field, and the map in the advice keeps the first
 * arbitrarily.
 */
@Documented
@Constraint(validatedBy = InTaskStatusVocabulary.Validator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface InTaskStatusVocabulary {

    String message() default "status must be one of {vocabulary}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<InTaskStatusVocabulary, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {

            // Null is @NotBlank's business, not ours. Blank is too: it is not in
            // the vocabulary either, but letting it through here means the caller
            // is told "status is required" rather than handed a list of values
            // when what they sent was nothing.
            if (value == null || value.isBlank()) {
                return true;
            }

            if (TaskStatus.contains(value)) {
                return true;
            }

            // The default message carries a placeholder rather than the values,
            // because the values live in the enum and a message written out by
            // hand goes stale the day a member is added. Interpolating here is
            // what keeps the two from drifting.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            context.getDefaultConstraintMessageTemplate()
                                    .replace("{vocabulary}", TaskStatus.vocabulary()))
                    .addConstraintViolation();

            return false;
        }
    }
}

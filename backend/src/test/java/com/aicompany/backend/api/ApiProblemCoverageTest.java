package com.aicompany.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract, asserted as a property of the code rather than of one response.
 *
 * <p>This test used to be {@code TaskExceptionHandlerScopeTest} and did the
 * opposite job: it pinned the exact set of exceptions a module-scoped advice
 * handled, so that the contract could not widen by accident. That risk is gone --
 * the contract is now global and widens by decision -- and the risk that replaced
 * it is the mirror image: a domain exception that nobody mapped, surfacing as a
 * 500 in production instead of as a failure here.
 */
class ApiProblemCoverageTest {

    private static final List<String> DOMAIN_EXCEPTION_PACKAGES = List.of(
            "com.aicompany.backend.project.exception",
            "com.aicompany.backend.task.exception");

    /**
     * AC-10, invariant I-6. Every domain exception that exists has a mapping.
     *
     * <p>Adding one without deciding what it looks like from the outside is the
     * failure mode this catches: it would otherwise reach the catch-all and be
     * reported as an internal error, which is both wrong and the hardest kind of
     * wrong to notice.
     */
    @Test
    void everyDomainExceptionIsMapped() {

        Set<Class<?>> mapped = mappedExceptionTypes();
        Set<Class<?>> declared = declaredDomainExceptions();

        assertThat(declared)
                .as("the scan must actually find the domain exceptions, or this test proves nothing")
                .isNotEmpty();

        assertThat(mapped)
                .as("""
                    every domain exception needs a mapping in ApiExceptionHandler. One that is \
                    missing does not fail loudly: it falls through to the catch-all and is \
                    reported as an internal error, which tells a caller nothing and hides a \
                    decision nobody took.""")
                .containsAll(declared);
    }

    /**
     * AC-8, invariants I-2 and I-3. The identifiers are unique, and they are the
     * thing clients branch on -- so a duplicate would make two different problems
     * indistinguishable from outside.
     */
    @Test
    void everyProblemHasItsOwnIdentifier() {

        List<String> slugs = Arrays.stream(ApiProblem.values()).map(ApiProblem::slug).toList();

        assertThat(slugs).doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(slugs)).hasSameSizeAs(slugs);

        assertThat(Arrays.stream(ApiProblem.values()).map(problem -> problem.type().toString()))
                .allMatch(type -> type.startsWith("urn:ai-company-os:problem:"));
    }

    /**
     * AC-8. The exact set, so that adding a problem is a deliberate act with a
     * reason rather than something that arrives with an unrelated change.
     */
    @Test
    void theSetOfProblemsIsExact() {

        assertThat(Arrays.stream(ApiProblem.values()).map(ApiProblem::slug))
                .containsExactlyInAnyOrder(
                        "validation-failed",
                        "malformed-request",
                        "invalid-parameter",
                        "unsupported-media-type",
                        "method-not-allowed",
                        "resource-not-found",
                        "project-not-found",
                        "project-name-conflict",
                        "archived-project-is-immutable",
                        "illegal-project-state-transition",
                        "task-not-found",
                        "archived-project-cannot-receive-tasks",
                        "archived-project-task-is-immutable",
                        "internal-error");
    }

    /**
     * Titles are prose and may be rewritten; identifiers are not. What must hold
     * is that neither is empty and that a status is always attached, since all
     * three travel together in every response.
     */
    @Test
    void everyProblemCarriesAStatusAndAReadableTitle() {

        for (ApiProblem problem : ApiProblem.values()) {
            assertThat(problem.status()).as(problem.name()).isNotNull();
            assertThat(problem.title()).as(problem.name()).isNotBlank();
            assertThat(problem.toDetail().getDetail()).as(problem.name()).isNotBlank();
        }
    }

    // --- helpers -----------------------------------------------------------

    private static Set<Class<?>> mappedExceptionTypes() {
        return Arrays.stream(ApiExceptionHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Stream.of(annotation.value()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Class<?>> declaredDomainExceptions() {

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RuntimeException.class));

        Set<Class<?>> found = new LinkedHashSet<>();
        for (String basePackage : DOMAIN_EXCEPTION_PACKAGES) {
            for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
                try {
                    found.add(Class.forName(definition.getBeanClassName()));
                } catch (ClassNotFoundException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return found;
    }
}

package com.aicompany.backend.api;

import com.aicompany.backend.agent.service.AgentService;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.run.service.RunService;
import com.aicompany.backend.llm.service.LlmCatalogService;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.usage.service.UsageService;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.plan.service.PlanningService;
import com.aicompany.backend.orchestrator.service.ExecutionService;
import com.aicompany.backend.harness.service.AgentTemplates;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.daily.service.DailyService;
import com.aicompany.backend.task.service.TaskService;
import com.aicompany.backend.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule P4, the closure clause, as a property of the code rather than of a
 * promise.
 *
 * <p>ADR-009 §3 says every write path that mutates an existing resource applies
 * the protocol, present or future, TASK-009 included. A sentence in a document
 * cannot enforce that: the way it fails is that somebody adds a write path, does
 * not think about preconditions, and nothing anywhere goes red. The protocol then
 * holds wherever it was remembered, which is the same as not holding -- a client
 * has no way to know which routes it covers.
 *
 * <p>So the rule is checked by reflection: a public service method that can write
 * to a row that already exists must take a {@link Precondition}. Creation is the
 * only exemption, and it is an exemption on the merits -- there is no earlier
 * state a caller could have seen.
 *
 * <p>The same clause that L7 needed, for the same reason, and one level up: this
 * is what stops "tanto questo caso è innocuo" from being available as a decision.
 */
class PreconditionCoverageTest {

    private static final List<Class<?>> SERVICES =
            List.of(TaskService.class, ProjectService.class, AgentService.class, RunService.class,
                    SoftwareService.class, LlmCatalogService.class, UsageService.class,
                    ProjectWorkspaceService.class, PlanningService.class, ExecutionService.class,
                    HarnessService.class, AgentTemplates.class, DailyService.class, AuthService.class);

    /**
     * Creation, and only creation. A row nobody has seen has no state a caller
     * could be stale about, which is the same argument ADR-006 §4 used to keep
     * {@code POST /api/tasks} out of rule L0.
     */
    private static final List<String> CREATION_METHODS = List.of("create",
            // PHASE 10 (ADR-021): producing a plan is creation too -- of phases and
            // tasks nobody has seen -- and replacing a draft is refused outright
            // once it is approved or worked on (PlanLockedException), which is the
            // staleness a tag would otherwise have guarded. recoverInterrupted runs
            // at startup, not on a client's request.
            "generate", "importFromWorkspace", "recoverInterrupted",
            // PHASE 12 (ADR-023): set membership -- an agent's harness and software, a
            // project's adopted resources -- is added by an idempotent PUT and removed
            // by an idempotent DELETE; neither mutates the agent or the project row.
            // Installing a template creates agents, and leaves existing ones untouched.
            "attach", "detach", "allowSoftware", "disallowSoftware", "adopt", "drop", "install", "installAll",
            // PHASE 13: carrying a day's unfinished items to another day is the
            // operator's bulk gesture on their own list; no single tag describes it.
            "carryOver",
            // PHASE 15 (ADR-024): signing in creates a session and counts failures on
            // the account row -- the server's bookkeeping, not a client's edit, and no
            // client can hold a tag before it has a credential. Signing out revokes
            // one's own session. Changing one's own password is guarded by the current
            // password, a stronger precondition than a tag. Admin edits of people
            // (update, resetPassword) do take the tag.
            "login", "logout", "changePassword");

    @Test
    void everyWritePathOnAnExistingRowTakesAPrecondition() {

        List<Method> unprotected = SERVICES.stream()
                .flatMap(service -> Arrays.stream(service.getDeclaredMethods()))
                .filter(PreconditionCoverageTest::isPublicInstanceMethod)
                .filter(method -> !isReadOnly(method))
                .filter(method -> !CREATION_METHODS.contains(method.getName()))
                .filter(method -> !takesAPrecondition(method))
                .toList();

        assertThat(unprotected)
                .as("""
                    Rule P4. A write path that does not take a Precondition cannot enforce one, \
                    and the way this rule breaks is silent: somebody adds a method, nobody \
                    thinks about staleness, and no test anywhere notices. If one of these is \
                    deliberately exempt, the exemption belongs in this test with its reason -- \
                    not in whoever happens to read the service next.""")
                .isEmpty();
    }

    /**
     * The other half, and the one that would rot first: the exemption has to keep
     * describing the code.
     *
     * <p>The first version of this test tried to infer "really is creation" from
     * the signature -- a creator takes no {@code Long} -- and it was wrong on the
     * first method it looked at. {@code TaskService.create} takes a
     * {@code projectId}: the identifier of a <em>different</em> row, which it reads
     * and does not mutate. Reflection cannot tell those two apart, and a heuristic
     * that cannot is worse than none, because it fails on correct code and teaches
     * whoever meets it to edit the test until it passes.
     *
     * <p>So the set is pinned instead, the way {@code ApiProblemCoverageTest} pins
     * the problems. Nothing is inferred: adding a write path fails this test until
     * somebody writes it down here, next to whether it takes a precondition and
     * why. That is the whole mechanism -- P4 asks for a decision, and this is what
     * makes one unavoidable.
     */
    @Test
    void theSetOfWritePathsIsExactAndEachOneSaysWhetherItTakesAPrecondition() {

        assertThat(writePaths(TaskService.class))
                .as("""
                    Rule P4 on the path TASK-009 added. assignToAgent mutates the same tasks row                     that assignToProject does, so it carries the same precondition and consumes                     the same tag. Dropping the parameter, or adding a write path without listing                     it here, fails this assertion -- which is the only thing that makes P4 a rule                     rather than an intention.""")
                .containsExactlyInAnyOrder(
                        "create",                      // exempt: no earlier state to be stale about
                        "assignToProject:Precondition",
                        "assignToAgent:Precondition",
                        "transition:Precondition",       // ADR-014: every edge, one path
                        "update:Precondition");          // TASK-016: the details

        assertThat(writePaths(ProjectService.class))
                .containsExactlyInAnyOrder(
                        "create",                      // exempt
                        "update:Precondition",
                        "archive:Precondition",
                        "restore:Precondition");

        // ADR-016: launching a run may start the task, so it is a write to an
        // existing row and carries the task's precondition. The executor's own
        // writes to the run (RunRecorder) are not a client path and not listed.
        assertThat(writePaths(RunService.class))
                .containsExactlyInAnyOrder("launch:Precondition");

        assertThat(writePaths(AgentService.class))
                .containsExactlyInAnyOrder(
                        "create",                      // exempt
                        "update:Precondition",
                        "activate:Precondition",
                        "deactivate:Precondition");

        // PHASE 8 (ADR-019). A launch reads the catalog and starts a process; it
        // writes no row, so it is not a write path and takes no precondition.
        assertThat(writePaths(SoftwareService.class))
                .containsExactlyInAnyOrder("create", "update:Precondition");

        assertThat(writePaths(LlmCatalogService.class))
                .containsExactlyInAnyOrder("classify:Precondition");

        assertThat(writePaths(UsageService.class))
                .containsExactlyInAnyOrder("anchor:Precondition");

        // PHASE 9 (ADR-020). Only the profile writes a row; scaffolding and
        // documents write files in the workspace, never the database.
        assertThat(writePaths(ProjectWorkspaceService.class))
                .containsExactlyInAnyOrder("configure:Precondition");

        // PHASE 10 (ADR-021). Approvals mutate existing rows and take the tag.
        // generate and importFromWorkspace are exempt the way create is: they
        // produce a plan nobody has seen, and they refuse to replace one that is
        // approved or worked on (PlanLockedException) -- the refusal the
        // precondition would otherwise have had to express.
        assertThat(writePaths(PlanningService.class))
                .containsExactlyInAnyOrder("generate", "importFromWorkspace", "recoverInterrupted",
                        "approvePlan:Precondition", "reviewPhase:Precondition");

        // PHASE 11 (ADR-021 §4-5). Both may move the task, so both take its tag.
        assertThat(writePaths(ExecutionService.class))
                .containsExactlyInAnyOrder("handoff:Precondition", "review:Precondition");

        assertThat(writePaths(HarnessService.class))
                .containsExactlyInAnyOrder("create", "configure:Precondition", "attach", "detach", "allowSoftware",
                        "disallowSoftware", "adopt", "drop");

        assertThat(writePaths(AgentTemplates.class)).containsExactlyInAnyOrder("install", "installAll");

        assertThat(writePaths(DailyService.class))
                .containsExactlyInAnyOrder("create", "update:Precondition", "remove:Precondition", "carryOver");
    }

    /**
     * Each write path as {@code name} or {@code name:Precondition}. Read paths --
     * the ones that declare {@code readOnly = true} -- are not write paths and are
     * not listed.
     */
    private static List<String> writePaths(Class<?> service) {
        return Arrays.stream(service.getDeclaredMethods())
                .filter(PreconditionCoverageTest::isPublicInstanceMethod)
                .filter(method -> !isReadOnly(method))
                .map(method -> takesAPrecondition(method)
                        ? method.getName() + ":Precondition"
                        : method.getName())
                .sorted()
                .toList();
    }

    private static boolean isPublicInstanceMethod(Method method) {
        return Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && method.getDeclaringClass() != Object.class;
    }

    /**
     * A read path declares itself: {@code @Transactional(readOnly = true)}. That
     * annotation is not decoration here -- it is what the class-level
     * {@code @Transactional} is being narrowed from, and TD-24 is the record of
     * what happens when a write path is allowed to go through one of these.
     */
    private static boolean isReadOnly(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        return transactional != null && transactional.readOnly();
    }

    private static boolean takesAPrecondition(Method method) {
        return Stream.of(method.getParameterTypes()).anyMatch(Precondition.class::equals);
    }
}

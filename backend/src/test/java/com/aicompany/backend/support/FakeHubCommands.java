package com.aicompany.backend.support;

import com.aicompany.backend.harness.hub.HubCommands;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Claude Code's and Codex's CLIs, as the Knowledge Hub sees them in tests
 * (PHASE 31): nothing is ever run; each call is recorded, and answered by the
 * first scripted answer whose arguments start the same way -- otherwise a
 * success with no output.
 */
public class FakeHubCommands implements HubCommands {

    public record Call(Tool tool, List<String> arguments) {
    }

    private record Answer(Tool tool, Predicate<List<String>> when, Result result) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final List<Tool> missing = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<Call>> hooks = new CopyOnWriteArrayList<>();

    /**
     * PHASE 37: runs before a call is answered -- to read a file the caller wrote for the
     * command and removes after it, or to leave behind what the real command would.
     */
    public void onRun(java.util.function.Consumer<Call> hook) {
        hooks.add(hook);
    }

    /** Answers the calls of {@code tool} whose arguments begin with {@code prefix}. */
    public void answer(Tool tool, List<String> prefix, int exitCode, String output) {
        answers.add(new Answer(tool, a -> a.size() >= prefix.size() && a.subList(0, prefix.size()).equals(prefix),
                new Result(exitCode, output, false)));
    }

    /** As {@link #answer}, for calls that also contain {@code argument} somewhere. */
    public void answerWith(Tool tool, List<String> prefix, String argument, int exitCode, String output) {
        answers.addFirst(new Answer(tool, a -> a.size() >= prefix.size() && a.subList(0, prefix.size()).equals(prefix)
                && a.contains(argument), new Result(exitCode, output, false)));
    }

    /** PHASE 37: as {@link #answer}, taking precedence over the answers scripted before it. */
    public void answerFirst(Tool tool, List<String> prefix, int exitCode, String output) {
        answers.addFirst(new Answer(tool, a -> a.size() >= prefix.size() && a.subList(0, prefix.size()).equals(prefix),
                new Result(exitCode, output, false)));
    }

    public void missing(Tool tool) {
        missing.add(tool);
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    public void reset() {
        calls.clear();
        answers.clear();
        missing.clear();
        hooks.clear();
    }

    @Override
    public Optional<Path> executable(Tool tool) {
        return missing.contains(tool) ? Optional.empty() : Optional.of(Path.of(tool.name().toLowerCase() + ".exe"));
    }

    @Override
    public Result run(Tool tool, List<String> arguments, Duration timeout) {
        Call call = new Call(tool, List.copyOf(arguments));
        calls.add(call);
        hooks.forEach(hook -> hook.accept(call));
        for (Answer answer : answers) {
            if (answer.tool() == tool && answer.when().test(arguments)) {
                return answer.result();
            }
        }
        return new Result(0, "", false);
    }
}

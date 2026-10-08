package com.aicompany.backend.support;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * An engine the tests script, in place of the HTTP one, for every Spring test.
 *
 * <p>By default it answers like the real echo provider would. A test can queue
 * answers or failures, and can hold the next call open ({@link #holdNextCall()})
 * to observe a run while it is {@code RUNNING}. Every request is recorded, so a
 * test can assert what the control plane actually sent.
 *
 * <p>The HTTP client itself is tested separately, against a real socket
 * ({@code HttpEngineClientTest}): what the control plane does with an answer and
 * how it reads one are two different questions.
 */
public class ScriptedEngineClient implements EngineClient {

    private final Deque<Function<Request, Completion>> script = new ArrayDeque<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch hold;
    private volatile CountDownLatch entered;
    private volatile int holdFrom;

    public synchronized void reset() {
        script.clear();
        requests.clear();
        release();
        modelsUnavailable = false;
        defaultModel = "echo:default";
        interrupted = false;
        hold = null;
        entered = null;
        holdFrom = 0;
    }

    public synchronized void answer(String output) {
        script.add(request -> new Completion(output, "stop", request.model() == null ? "echo:default" : request.model(),
                10, 20, 5));
    }

    public synchronized void answerWith(Completion completion) {
        script.add(request -> completion);
    }

    public synchronized void fail(String type, String detail) {
        script.add(request -> {
            throw new EngineFailure(type, detail);
        });
    }

    public synchronized void explode() {
        script.add(request -> {
            throw new IllegalStateException("a bug inside the control plane, SECRET-INTERNALS");
        });
    }

    /** The next call blocks until {@link #release()}; {@link #awaitEntered()} waits for it to start. */
    public synchronized void holdNextCall() {
        holdCallsAfter(0);
    }

    /** PHASE 32 (TD-43): the next {@code passing} calls answer; those after them block until {@link #release()}. */
    public synchronized void holdCallsAfter(int passing) {
        hold = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        holdFrom = requests.size() + passing;
    }

    public void release() {
        CountDownLatch current = hold;
        if (current != null) {
            current.countDown();
        }
    }

    public void awaitEntered() throws InterruptedException {
        if (!entered.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the engine was never called");
        }
    }

    private volatile boolean modelsUnavailable;
    private volatile String defaultModel = "echo:default";
    private volatile boolean interrupted;

    /** PHASE 32 (TD-39): whether a held call was interrupted -- a cancelled run gives its thread back at once. */
    public boolean interrupted() {
        return interrupted;
    }

    /** PHASE 32 (TD-54): the model the engine would use for a run that names none. */
    public void defaultModel(String key) {
        defaultModel = key;
    }

    public void modelsUnavailable() {
        modelsUnavailable = true;
    }

    @Override
    public ModelList models() {
        if (modelsUnavailable) {
            modelsUnavailable = false;
            throw new EngineFailure("urn:ai-company-os:run-failure:engine-unreachable", "Nothing answered");
        }
        return new ModelList(defaultModel, List.of(
                new ModelInfo("echo:default", "echo", true, "Deterministic echo; not a language model", false),
                new ModelInfo("anthropic:claude-opus-5", "anthropic", false, "Set ANTHROPIC_API_KEY to enable", true)));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public Completion complete(Request request) {
        CountDownLatch waitFor;
        Function<Request, Completion> next;
        synchronized (this) {
            requests.add(request);
            waitFor = hold != null && requests.size() > holdFrom ? hold : null;
            if (waitFor != null && entered != null) {
                entered.countDown();
            }
            next = script.poll();
        }
        if (waitFor != null) {
            try {
                waitFor.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
        }
        if (next != null) {
            return next.apply(request);
        }
        return new Completion("[echo] " + request.user(), "stop",
                request.model() == null ? "echo:default" : request.model(), 7, 7, 1);
    }
}

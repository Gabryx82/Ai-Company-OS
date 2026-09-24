package com.aicompany.backend.binding;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Where an agent can work (ADR-025 §2): the AI Engine, or an application or CLI
 * of the Software Hub that the Master Orchestrator hands the task to. Read from
 * {@code catalog/execution-targets.json}; a target names the software it opens,
 * how the prompt reaches it, and which providers' models it can run.
 */
@Component
public class ExecutionTargetCatalog {

    public static final String ENGINE = "engine";

    /** How the prompt reaches the target. */
    public enum Delivery {
        /** The control plane runs it through the AI Engine; the result comes back to the console. */
        ENGINE_RUN,
        /** A CLI opened in the project folder with the prompt as its argument. */
        CLI_PROMPT,
        /** An IDE opened on the project folder; the prompt is copied for its agent. */
        IDE_FOLDER,
        /** A desktop app opened; the prompt is copied to be pasted. */
        APP_PASTE,
        /** A web app opened by the console; the prompt is copied to be pasted. */
        WEB_PASTE,
        /** Nothing opened: the package is written, the operator works. */
        MANUAL
    }

    public record ExecutionTarget(String key, String name, String software, Delivery delivery, List<String> providers,
                                  String contextFile, String howItWorks) {
        public ExecutionTarget {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }

        public boolean isEngine() {
            return delivery == Delivery.ENGINE_RUN;
        }
    }

    private final List<ExecutionTarget> targets;

    public ExecutionTargetCatalog() {
        JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = new ClassPathResource("catalog/execution-targets.json").getInputStream()) {
            this.targets = List.of(json.readValue(in, ExecutionTarget[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("catalog/execution-targets.json is unreadable", e);
        }
    }

    public List<ExecutionTarget> all() {
        return targets;
    }

    public Optional<ExecutionTarget> find(String key) {
        String wanted = key == null || key.isBlank() ? ENGINE : key;
        return targets.stream().filter(t -> t.key().equals(wanted)).findFirst();
    }
}

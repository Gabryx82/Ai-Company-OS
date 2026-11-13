package com.aicompany.backend.sync;

import com.aicompany.backend.agent.model.Agent;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * PHASE 37 (ADR-043): an agent as a tool outside AI Company OS receives it -- who it
 * is, what it was told, the model it runs on and the skills it carries. Built once
 * from the agent's row and harness, so that OpenClaw, Open WebUI and LiteLLM are
 * all given the same agent, each in its own shape.
 *
 * @param instructions the agent's own text, section by section, as a handoff package
 *                     says it under "Chi sei" -- never a project's rules, which belong
 *                     to the task the agent is given
 * @param model        the agent's model, {@code <provider>:<model>}, or null when the
 *                     agent leaves the choice to the tool
 */
public record AgentProfile(Long id, String name, String role, @Nullable String specialization,
                           @Nullable String description, String instructions, @Nullable String model,
                           List<Skill> skills) {

    /** The identifiers AI Company OS gives its agents in other tools: recognisable, and never a tool's own. */
    public static final String PREFIX = "aicos-";

    /**
     * @param essential the part of the skill an engine run would get (ADR-041 §7), for
     *                  the tools that cannot load a skill file by themselves
     */
    public record Skill(String key, String name, @Nullable String description, @Nullable String essential) {
    }

    public AgentProfile {
        skills = List.copyOf(skills);
    }

    public static AgentProfile of(Agent agent, List<Skill> skills) {
        StringBuilder text = new StringBuilder();
        section(text, "Istruzioni di ruolo", agent.getSystemPrompt());
        section(text, "Responsabilità", agent.getResponsibilities());
        section(text, "Direttive", agent.getDirectives());
        section(text, "Limiti", agent.getLimits());
        section(text, "Output atteso", agent.getOutputFormat());
        section(text, "Politica di contesto", agent.getContextPolicy());
        section(text, "Capability", agent.getCapabilities());
        section(text, "Dominio", agent.getDomain());
        return new AgentProfile(agent.getId(), agent.getName(), agent.getRole(), blankToNull(agent.getSpecialization()),
                blankToNull(agent.getDescription()), text.toString().strip(), blankToNull(agent.getModel()), skills);
    }

    /** {@code aicos-frontend-developer}: lower-case ASCII letters, digits and single dashes, at most 48 characters. */
    public String slug() {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "agent-" + id;
        }
        String whole = PREFIX + slug;
        return whole.length() <= 48 ? whole : whole.substring(0, 48).replaceAll("-+$", "");
    }

    /** The provider of {@link #model}, or null. */
    public @Nullable String provider() {
        return model == null ? null : model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
    }

    /** The model's name at its provider ({@code qwen3.5:9b} of {@code ollama:qwen3.5:9b}), or null. */
    public @Nullable String modelName() {
        return model == null || !model.contains(":") ? null : model.substring(model.indexOf(':') + 1);
    }

    /** One line that says what the agent is for: its description, or its role and specialization. */
    public String summary() {
        if (description != null) {
            return description;
        }
        return specialization == null ? role : role + " — " + specialization;
    }

    /**
     * The system prompt of the agent in a chat tool: who it is, its instructions and
     * what its skills say in short. The same words every such tool gets.
     */
    public String systemPrompt() {
        StringBuilder text = new StringBuilder("Sei ").append(name).append(" — ").append(role);
        if (specialization != null) {
            text.append(", ").append(specialization);
        }
        text.append(".\n");
        if (description != null) {
            text.append('\n').append(description).append('\n');
        }
        if (!instructions.isEmpty()) {
            text.append('\n').append(instructions).append('\n');
        }
        if (!skills.isEmpty()) {
            text.append("\n## Skill\n");
            for (Skill skill : skills) {
                text.append("\n### ").append(skill.name()).append('\n');
                String body = skill.essential() != null ? skill.essential() : skill.description();
                if (body != null) {
                    text.append('\n').append(body.strip()).append('\n');
                }
            }
        }
        return text.toString().strip() + "\n";
    }

    /**
     * What was published, in 64 hexadecimal characters: it changes when anything a tool
     * is given changes, and only then -- the agent's version counter also moves when a
     * task is assigned, which a tool does not care about.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        for (String part : new String[] {name, role, specialization, description, instructions, model}) {
            canonical.append(part == null ? "" : part).append('\u0000');
        }
        for (Skill skill : skills) {
            canonical.append(skill.key()).append('\u0001').append(skill.name()).append('\u0001')
                    .append(skill.description() == null ? "" : skill.description()).append('\u0001')
                    .append(skill.essential() == null ? "" : skill.essential()).append('\u0000');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static void section(StringBuilder text, String title, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            text.append("## ").append(title).append("\n\n").append(value.strip()).append("\n\n");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

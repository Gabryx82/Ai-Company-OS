package com.aicompany.backend.assistant;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentStatus;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.exception.EngineUnavailableException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The console's quick assistant: one question about the ecosystem, answered by a local model
 * from a snapshot of the projects, tasks and agents the control plane holds.
 *
 * <p>It never spends money nobody recorded: only a model the engine serves without billing is
 * asked (the engine's default when it is one, otherwise the first local model available), and the
 * deterministic echo model is not a model to talk to. With none, the question is refused with
 * what to do instead. Nothing is stored: the console keeps the conversation and sends it back.
 */
@Service
public class AssistantService {

    public enum Speaker { USER, ASSISTANT }

    public record Turn(Speaker speaker, String text) {
    }

    public record Answer(String answer, String model) {
    }

    static final int MAX_TOKENS = 700;
    private static final int LISTED = 25;

    private final EngineClient engine;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final AgentRepository agents;

    public AssistantService(EngineClient engine, ProjectRepository projects, TaskRepository tasks,
                            AgentRepository agents) {
        this.engine = engine;
        this.projects = projects;
        this.tasks = tasks;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public Answer ask(String question, List<Turn> history) {
        String model = localModel().orElseThrow(() -> new EngineUnavailableException(
                "Nessun modello locale gratuito disponibile per l'assistente: avvia Ollama con almeno un modello."));
        String system = system();
        String user = conversation(history, question);
        try {
            EngineClient.Completion completion = engine.complete(new EngineClient.Request(model, system, user,
                    MAX_TOKENS, "assistant-" + UUID.randomUUID(), Map.of("purpose", "assistant")));
            return new Answer(completion.output().strip(), completion.model());
        } catch (EngineFailure failure) {
            throw new EngineUnavailableException(failure.detail());
        }
    }

    /** A model that costs nothing per call: the engine's default if it is one, else the first local one. */
    Optional<String> localModel() {
        EngineClient.ModelList list;
        try {
            list = engine.models();
        } catch (EngineFailure failure) {
            throw new EngineUnavailableException(failure.detail());
        }
        List<EngineClient.ModelInfo> usable = list.models().stream()
                .filter(m -> m.available() && !m.billed() && !"echo".equals(m.provider()))
                .toList();
        return usable.stream().filter(m -> m.id().equals(list.defaultModel())).findFirst()
                .or(() -> usable.stream().findFirst())
                .map(EngineClient.ModelInfo::id);
    }

    private String system() {
        List<Project> active = projects.findAllByStatusOrderByIdAsc(ProjectStatus.ACTIVE);
        List<Task> all = tasks.findAllWithAssociations();
        List<Agent> enabled = agents.findAllByStatusOrderByIdAsc(AgentStatus.ACTIVE);
        StringBuilder s = new StringBuilder("""
                Sei l'assistente di AI Company OS, l'ambiente in cui una persona costruisce software con agenti AI.
                Rispondi in italiano, in modo breve e concreto: al massimo sei frasi o un elenco corto.
                Usa solo i dati qui sotto; se la risposta non c'è, dillo e indica la pagina della console dove guardare
                (Dashboard, Agenda, Progetti, Task, Impara, Agenti, Ufficio 3D, Ecosistema 3D, Modelli, OpenClaw,
                Software Hub, Terminale, Chat, Integrazioni, Knowledge Hub, Mockup Hub, Second Brain, Consumi, Impostazioni).
                Non inventare numeri, nomi o stati.

                STATO ATTUALE
                """);
        s.append("Progetti attivi: ").append(active.size()).append('\n');
        for (Project p : active.stream().limit(LISTED).toList()) {
            List<Task> own = all.stream().filter(t -> Objects.equals(t.getProjectId(), p.getId())).toList();
            long done = own.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
            s.append("- ").append(p.getName()).append(": ").append(own.size()).append(" task, ")
                    .append(done).append(" completate; piano ").append(p.getPlanStatus()).append('\n');
        }
        Map<TaskStatus, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        s.append("Task: ").append(byStatus.getOrDefault(TaskStatus.OPEN, 0L)).append(" da fare, ")
                .append(byStatus.getOrDefault(TaskStatus.IN_PROGRESS, 0L)).append(" in corso, ")
                .append(byStatus.getOrDefault(TaskStatus.DONE, 0L)).append(" completate\n");
        all.stream().filter(t -> t.getStatus() != TaskStatus.DONE).limit(LISTED).forEach(t -> s.append("- #")
                .append(t.getId()).append(' ').append(t.getTitle()).append(" [")
                .append(t.getStatus() == TaskStatus.IN_PROGRESS ? "in corso" : "da fare").append(", priorità ")
                .append(t.getPriority()).append(", agente ")
                .append(t.getAgent() == null ? "nessuno" : t.getAgent().getName()).append("]\n"));
        s.append("Agenti abilitati: ").append(enabled.size()).append('\n');
        enabled.stream().limit(LISTED).forEach(a -> s.append("- ").append(a.getName()).append(" (")
                .append(a.getRole()).append(")\n"));
        return s.toString();
    }

    private static String conversation(List<Turn> history, String question) {
        StringBuilder s = new StringBuilder();
        for (Turn turn : history) {
            s.append(turn.speaker() == Speaker.USER ? "Utente: " : "Assistente: ")
                    .append(turn.text().strip()).append("\n\n");
        }
        return s.append("Utente: ").append(question.strip()).append("\n\nAssistente:").toString();
    }
}

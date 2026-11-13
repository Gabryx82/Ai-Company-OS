package com.aicompany.backend.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The console's quick assistant: a question, an answer from a local model, nothing stored. */
@RestController
public class AssistantController {

    public record AssistantTurn(@NotNull AssistantService.Speaker speaker, @NotBlank @Size(max = 4000) String text) {
    }

    public record AssistantQuestion(@NotBlank @Size(max = 2000) String question,
                                    @Nullable @Size(max = 12) List<@Valid AssistantTurn> history) {
    }

    public record AssistantAnswer(String answer, String model) {
    }

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/api/assistant/ask")
    public AssistantAnswer ask(@Valid @RequestBody AssistantQuestion body) {
        List<AssistantService.Turn> history = body.history() == null ? List.of()
                : body.history().stream().map(t -> new AssistantService.Turn(t.speaker(), t.text())).toList();
        AssistantService.Answer answer = assistant.ask(body.question(), history);
        return new AssistantAnswer(answer.answer(), answer.model());
    }
}

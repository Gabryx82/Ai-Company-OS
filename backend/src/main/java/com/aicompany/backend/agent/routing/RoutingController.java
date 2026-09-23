package com.aicompany.backend.agent.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent suggestions for free text: the typed successor of {@code POST /api/orchestrator}
 * (TD-08), which took a {@code String} and returned a {@code String}.
 *
 * <p>A {@code POST} because the text can be long and is not a resource; it
 * writes nothing. The suggestions for an existing task are
 * {@code GET /api/tasks/{id}/agent-suggestions}.
 */
@RestController
public class RoutingController {

    private final AgentRouter router;

    public RoutingController(AgentRouter router) {
        this.router = router;
    }

    public record RoutingRequest(
            @NotBlank(message = "text is required")
            @Size(max = 10_000, message = "text must be at most 10000 characters")
            String text) {
    }

    @PostMapping("/api/routing/suggestions")
    public List<AgentRouter.Suggestion> suggest(@Valid @RequestBody RoutingRequest request) {
        return router.suggest(request.text());
    }
}

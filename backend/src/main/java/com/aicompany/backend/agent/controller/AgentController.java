package com.aicompany.backend.agent.controller;


import com.aicompany.backend.agent.dto.AgentResponse;
import com.aicompany.backend.agent.service.AgentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
@CrossOrigin
public class AgentController {


    private final AgentService service;


    public AgentController(AgentService service){
        this.service = service;
    }


    @GetMapping
    public List<AgentResponse> getAgents(){

        return service.getAllAgents()
                .stream()
                .map(AgentResponse::from)
                .toList();

    }
}

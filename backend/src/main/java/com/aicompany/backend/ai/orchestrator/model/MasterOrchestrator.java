package com.aicompany.backend.ai.orchestrator.model;

import org.springframework.stereotype.Service;

@Service
public class MasterOrchestrator {

    public String analyze(String request){

        String lower = request.toLowerCase();

        if(lower.contains("ecommerce")){
            return "Backend Agent + Database Agent";
        }

        if(lower.contains("cms")){
            return "Backend Agent + Frontend Agent";
        }

        if(lower.contains("marketing")){
            return "Marketing Agent";
        }

        if(lower.contains("social")){
            return "Social Media Agent";
        }

        return "General AI Agent";

    }

}
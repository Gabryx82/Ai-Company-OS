package com.aicompany.backend.agent.repository;

import com.aicompany.backend.agent.model.Agent;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;


@Component
public class AgentInitializer implements CommandLineRunner {


    private final AgentRepository repository;


    public AgentInitializer(AgentRepository repository){
        this.repository = repository;
    }



    @Override
    public void run(String... args){


        if(repository.count()==0){


            repository.save(
                    new Agent(
                            "Code Architect",
                            "Software Engineer",
                            "Backend architecture and system design"
                    )
            );


            repository.save(
                    new Agent(
                            "Frontend Developer",
                            "Frontend Engineer",
                            "React TypeScript UI development"
                    )
            );


            repository.save(
                    new Agent(
                            "Database Specialist",
                            "Database Engineer",
                            "PostgreSQL and data modeling"
                    )
            );


            System.out.println("AI COMPANY OS: Agents initialized");

        }

    }
}
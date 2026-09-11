package com.aicompany.backend.agent.model;

import jakarta.persistence.*;

@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String specialization;

    @Column(nullable = false)
    private boolean active;


    public Agent(){}


    public Agent(String name, String role, String specialization){
        this.name = name;
        this.role = role;
        this.specialization = specialization;
        this.active = true;
    }


    public Long getId(){
        return id;
    }


    public String getName(){
        return name;
    }


    public String getRole(){
        return role;
    }


    public String getSpecialization(){
        return specialization;
    }


    public boolean isActive(){
        return active;
    }
}

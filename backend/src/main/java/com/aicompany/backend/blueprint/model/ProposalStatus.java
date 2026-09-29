package com.aicompany.backend.blueprint.model;

/** A proposal waits for a person; accepting it creates a task, never applies a diff. */
public enum ProposalStatus { PROPOSED, ACCEPTED, REJECTED }

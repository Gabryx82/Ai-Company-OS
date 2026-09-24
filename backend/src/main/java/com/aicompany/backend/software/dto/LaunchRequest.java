package com.aicompany.backend.software.dto;

/**
 * What a launch may ask for: a project, whose workspace folder -- read from the
 * database, never from this body -- becomes the program's folder (ADR-019 I2).
 * Everything else about the command line comes from the catalog.
 */
public record LaunchRequest(Long projectId) {
}

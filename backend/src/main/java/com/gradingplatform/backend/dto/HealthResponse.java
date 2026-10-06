package com.gradingplatform.backend.dto;

/** Body of `GET /health`. Only the status: nothing about the database or the host is exposed. */
public record HealthResponse(String status) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
}

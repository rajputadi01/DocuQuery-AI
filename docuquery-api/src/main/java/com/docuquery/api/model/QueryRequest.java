package com.docuquery.api.model;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
    @NotBlank(message = "Question cannot be empty")
    String question
) {}
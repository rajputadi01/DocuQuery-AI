package com.docuquery.api.model;

import java.util.List;

public record QueryResponse(
    String answer,
    String documentId,
    Double confidenceScore,
    List<String> sourceSnippets
) {}
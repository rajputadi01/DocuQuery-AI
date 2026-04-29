package com.docuquery.api.model;

import java.util.List;

public record DocumentSummary(
    String documentType,
    String shortSummary,
    List<String> keyEntities
) {}
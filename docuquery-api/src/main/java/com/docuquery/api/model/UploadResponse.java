package com.docuquery.api.model;

public record UploadResponse(
    String documentId,
    String fileName,
    String message
) {}
package com.docuquery.api.service;

import com.docuquery.api.model.DocumentSummary;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface MetadataExtractor {

    // INTERVIEW POINT: This is Declarative AI Orchestration. We define the guardrails 
    // and the required output type (DocumentSummary), and the framework handles the rest.
    @SystemMessage({
        "You are an expert document analyzer.",
        "Extract the requested information from the provided document text.",
        "Return the result strictly conforming to the requested JSON structure.",
        "Do not include any conversational text or markdown formatting blocks."
    })
    DocumentSummary extractMetadata(@UserMessage String documentText);
}
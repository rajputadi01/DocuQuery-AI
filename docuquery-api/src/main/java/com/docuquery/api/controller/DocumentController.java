package com.docuquery.api.controller;

import com.docuquery.api.exception.CapacityExceededException;
import com.docuquery.api.model.DocumentSummary;
import com.docuquery.api.model.QueryRequest;
import com.docuquery.api.model.UploadResponse;
import com.docuquery.api.service.DocumentService;
import com.docuquery.api.service.RAGService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final RAGService ragService;

    public DocumentController(DocumentService documentService, RAGService ragService) {
        this.documentService = documentService;
        this.ragService = ragService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(@RequestParam("file") MultipartFile file) 
    {
        if (documentService.getDocumentCount() >= 5) 
        {
            throw new CapacityExceededException("Server capacity reached. Maximum of 5 active documents allowed. Please delete an existing document first.");
        }
        String documentId = documentService.processAndStoreDocument(file);
        UploadResponse response = new UploadResponse(
            documentId,
            file.getOriginalFilename(),
            "Document successfully uploaded and parsed."
        );
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    // NEW: Produces TEXT_EVENT_STREAM for real-time SSE Streaming
    @PostMapping(value = "/{id}/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryDocumentStream(
            @PathVariable("id") String documentId,
            @Valid @RequestBody QueryRequest request) { 
        
        documentService.verifyDocumentExists(documentId); 
        return ragService.queryDocumentStream(documentId, request.question());
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<DocumentSummary> getDocumentSummary(@PathVariable("id") String documentId) {
        documentService.verifyDocumentExists(documentId); 
        DocumentSummary summary = ragService.generateSummary(documentId);
        return ResponseEntity.ok(summary);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") String documentId) {
        documentService.deleteDocument(documentId);
        ragService.deleteDocumentContext(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/chat/history")
    public ResponseEntity<List<Map<String, String>>> getChatHistory(@PathVariable("id") String documentId) {
        documentService.verifyDocumentExists(documentId);
        return ResponseEntity.ok(ragService.getChatHistory(documentId));
    }

    @DeleteMapping("/{id}/chat/reset")
    public ResponseEntity<Void> resetChat(@PathVariable("id") String documentId) {
        documentService.verifyDocumentExists(documentId);
        ragService.resetChatSession(documentId);
        return ResponseEntity.noContent().build();
    }
}
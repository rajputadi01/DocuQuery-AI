package com.docuquery.api.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.docuquery.api.exception.DocumentNotFoundException;
import com.docuquery.api.model.DocumentSummary;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Map<String, String> rawTextStore = new ConcurrentHashMap<>();

    // Inject the LangChain AI Beans we created in Sub-Phase 3.1
    public DocumentService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }
    
    // Centralized guardrail to check if the document exists in our in-memory map
    public void verifyDocumentExists(String documentId) 
    {
        if (!rawTextStore.containsKey(documentId)) {
            throw new DocumentNotFoundException("Document ID '" + documentId + "' not found. It may have been cleared from memory after a server restart.");
        }
    }
    public String processAndStoreDocument(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload an empty file.");
        }

        if (!"application/pdf".equals(file.getContentType())) {
            throw new IllegalArgumentException("Only PDF files are currently supported.");
        }

        try {
            // Extract text using PDFBox
            String extractedText = extractTextFromPdf(file);
            
            if (extractedText.trim().isEmpty()) {
                throw new IllegalArgumentException("The uploaded PDF contains no readable text.");
            }
            
            // Generate a unique ID for this document
            String documentId = UUID.randomUUID().toString();
            
            // Store the raw text for summary extraction
            rawTextStore.put(documentId, extractedText);
            
            // 1. Create Metadata so we can filter by document later
            Metadata metadata = new Metadata();
            metadata.put("documentId", documentId);
            metadata.put("fileName", file.getOriginalFilename());

            // 2. Create the LangChain Document
            Document document = Document.from(extractedText, metadata);

            // 3. Chunking: Split the document into smaller pieces
            // Max 500 characters per chunk, with a 50-character overlap to preserve sentence context.
            DocumentSplitter splitter = DocumentSplitters.recursive(1000, 150);
            List<TextSegment> segments = splitter.split(document);

            // 4. Embedding: Send the chunks to Gemini to turn them into mathematical vectors
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 5. Storage: Save both the vectors and the original text chunks in memory
            embeddingStore.addAll(embeddings, segments);
            
            return documentId;

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse the PDF document: " + e.getMessage());
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            return pdfStripper.getText(document);
        }
    }
    
    public String getRawDocumentText(String documentId) {
        return rawTextStore.get(documentId);
    }
    
    public int getDocumentCount() {
        return this.rawTextStore.size(); 
    }
    
    public void deleteDocument(String documentId) {
        verifyDocumentExists(documentId);
        rawTextStore.remove(documentId);
    }
}
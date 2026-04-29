package com.docuquery.api.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.docuquery.api.exception.DocumentNotFoundException;
import com.docuquery.api.exception.PromptInjectionException;
import com.docuquery.api.model.DocumentSummary;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RAGService {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel; // NEW
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    
    private final MetadataExtractor metadataExtractor;
    private final DocumentService documentService;
    
    private final Map<String, DocumentSummary> summaryCache = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> chatSessions = new ConcurrentHashMap<>();
    private final Set<String> deletedDocumentIds = ConcurrentHashMap.newKeySet();

    private static final List<String> FORBIDDEN_PHRASES = List.of(
        "ignore previous", "system prompt", "forget your", "bypass", "jailbreak", "you are now"
    );

    public RAGService(ChatLanguageModel chatLanguageModel, 
                      StreamingChatLanguageModel streamingChatLanguageModel,
                      EmbeddingModel embeddingModel, 
                      EmbeddingStore<TextSegment> embeddingStore,
                      MetadataExtractor metadataExtractor,
                      DocumentService documentService) {
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.metadataExtractor = metadataExtractor;
        this.documentService = documentService;
    }
    
    public void deleteDocumentContext(String documentId) {
        summaryCache.remove(documentId);
        chatSessions.remove(documentId);
        deletedDocumentIds.add(documentId);
    }

    public void resetChatSession(String documentId) {
        chatSessions.remove(documentId);
    }

    public List<Map<String, String>> getChatHistory(String documentId) {
        List<ChatMessage> history = chatSessions.getOrDefault(documentId, new ArrayList<>());
        List<Map<String, String>> formattedHistory = new ArrayList<>();
        for (ChatMessage msg : history) {
            formattedHistory.add(Map.of("role", msg.type().toString(), "content", msg.text()));
        }
        return formattedHistory;
    }

    private void validatePrompt(String question) {
        String lowerQuestion = question.toLowerCase();
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lowerQuestion.contains(phrase)) {
                throw new PromptInjectionException("Prompt injection detected. Request rejected.");
            }
        }
    }

    public DocumentSummary generateSummary(String documentId) {
        if (deletedDocumentIds.contains(documentId)) {
            throw new DocumentNotFoundException("Document has been deleted.");
        }
        if (summaryCache.containsKey(documentId)) {
            return summaryCache.get(documentId);
        }
        String text = documentService.getRawDocumentText(documentId);
        DocumentSummary summary = metadataExtractor.extractMetadata(text);
        summaryCache.put(documentId, summary);
        return summary;
    }

    // NEW: Stream-based Query Execution
    public SseEmitter queryDocumentStream(String documentId, String question) {
        if (deletedDocumentIds.contains(documentId)) {
            throw new DocumentNotFoundException("Document has been deleted.");
        }
        validatePrompt(question);

        SseEmitter emitter = new SseEmitter(120000L); // 2-minute timeout

        // 1. Vector Search
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(3)
                .filter(metadataKey("documentId").isEqualTo(documentId))
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        StringBuilder contextBuilder = new StringBuilder();
        List<String> sources = new ArrayList<>();
        double highestScore = 0.0;

        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            String chunkText = match.embedded().text();
            contextBuilder.append(chunkText).append("\n\n");
            sources.add(chunkText);
            if (match.score() > highestScore) {
                highestScore = match.score();
            }
        }
        
        String context = contextBuilder.toString();
        
        // Push metadata to frontend immediately
        try {
            emitter.send(Map.of("type", "metadata", "score", highestScore, "sources", sources));
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        if (context.trim().isEmpty()) {
            try {
                emitter.send(Map.of("type", "token", "content", "No relevant information found."));
                emitter.send(Map.of("type", "complete"));
                emitter.complete();
            } catch (Exception e) {}
            return emitter;
        }

        List<ChatMessage> history = chatSessions.getOrDefault(documentId, new ArrayList<>());

        String systemPrompt = "You are an enterprise document intelligence assistant. " +
                "Answer the user's question based strictly on the provided context. " +
                "Do not hallucinate.\n\nContext:\n" + context;

        List<ChatMessage> messagesToSend = new ArrayList<>();
        messagesToSend.add(SystemMessage.from(systemPrompt));
        messagesToSend.addAll(history); 
        messagesToSend.add(UserMessage.from(question)); 

        // 2. Stream generation
        try {
            streamingChatLanguageModel.generate(messagesToSend, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    try {
                        emitter.send(Map.of("type", "token", "content", token));
                    } catch (Exception e) {}
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    history.add(UserMessage.from(question));
                    history.add(response.content());
                    
                    List<ChatMessage> prunedHistory = history;
                    if (history.size() > 6) {
                        prunedHistory = new ArrayList<>(history.subList(history.size() - 6, history.size()));
                    }
                    chatSessions.put(documentId, prunedHistory);
                    
                    try {
                        emitter.send(Map.of("type", "complete"));
                        emitter.complete();
                    } catch (Exception e) {}
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("[RAG-Fallback] Stream error: " + error.getMessage());
                    try {
                        emitter.send(Map.of("type", "token", "content", "\n\n⚠️ AI generation failed mid-stream."));
                        emitter.send(Map.of("type", "complete"));
                        emitter.complete();
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {
            // Instant Degradation Fallback
            try {
                emitter.send(Map.of("type", "token", "content", "⚠️ AI service is temporarily degraded. Returning raw context chunks instead:\n\n" + context));
                emitter.send(Map.of("type", "complete"));
                emitter.complete();
            } catch (Exception ex) {}
        }

        return emitter;
    }
}
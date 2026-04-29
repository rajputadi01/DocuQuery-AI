
# DocuQuery AI — Architecture

This document is the authoritative technical reference for DocuQuery AI's system architecture. It is intended for use by developers, technical reviewers, and AI tools to understand, reproduce, or extend the system design.

**Live Demo:** https://documentquery.vercel.app/
**Stack:** Java 21 · Spring Boot 3 · LangChain4j · Gemini · React · Vercel · Render

---

**ZONE 1: Client Tier — React Frontend (Deployed on Vercel)**

Technology: React, Vite, Axios (REST calls), Fetch API (SSE stream consumer), react-markdown, Lucide React icons, CSS.

This zone represents everything the user sees and interacts with.

**Document Library (Left Panel):**
The left panel maintains a stateful document library using React's `useState`. It tracks up to 5 concurrently active documents, each identified by a unique UUID `documentId` returned from the backend on upload. Each document card in the library displays the original filename, a shortened document ID hash, and an absolute upload timestamp. A document count indicator shows "ACTIVE MEMORY (X/5)" to communicate the capacity guardrail to the user. Each card has a red trash icon that triggers document deletion. Clicking a document card switches the entire right panel context — summary and chat history — to that document's session. A frontend file-type validation check disables the upload button and shows an alert if the user selects a non-PDF file, providing a first layer of validation before any network call is made.

**Dual-Tab Dashboard (Right Panel):**
The right panel has two tabs — "Structured Output" and "RAG Assistant."

The Structured Output tab renders the cached JSON metadata returned by the backend. It displays three fields: Document Type (rendered as a styled badge), Executive Summary (a short paragraph), and Key Entities (rendered as a styled list). A "Generate JSON Metadata" button triggers the API call. Once generated, the result is cached in the backend and subsequent clicks return instantly at zero API cost.

The RAG Assistant tab is a scrolling chat interface. It differentiates between user prompt bubbles (right-aligned) and AI response bubbles (left-aligned). AI response bubbles use `react-markdown` to safely parse and render LLM formatting including bullet lists, bold text, and inline code. Each AI bubble contains two enterprise auditability widgets rendered inline: a color-coded "Relevance Score" badge showing the vector similarity percentage (e.g. "⚡ Relevance: 89.4%"), and an expandable accordion toggle labeled "View Sources" that reveals the exact raw PDF text chunks that were retrieved and passed to the LLM as context. A landing state fills the right panel when no document is selected, showing three feature cards describing the app's capabilities.

**SSE Streaming Decoder:**
The chat interface uses the browser's native `fetch` API with a `ReadableStream` reader to consume Server-Sent Events. As tokens stream from the backend, the UI appends them character-by-character to the active AI bubble, creating a real-time typing effect. This reduces perceived latency from approximately 6 seconds (blocking HTTP) to under 500ms for first token. The SSE stream carries two types of events: a metadata event (sent first, containing the confidence score and source chunks) and token events (sent continuously until the LLM finishes generating). The frontend parses these separately and renders them into the appropriate UI slots.

**Data flow from Zone 1 to Zone 2:**
- `POST /api/documents/upload` — multipart form upload of a PDF file
- `GET /api/documents/{id}/summary` — fetches structured metadata
- `POST /api/documents/{id}/query` — opens an SSE stream for the RAG chat
- `DELETE /api/documents/{id}` — triggers tri-layer document deletion
- `GET /api/documents/{id}/chat/history` — fetches conversation history
- `DELETE /api/documents/{id}/chat/reset` — clears chat session without deleting document
- `GET /api/health` — health check (also pinged by cron job)

The SSE stream response arrow should be drawn as a **dashed blue arrow flowing right-to-left** from Zone 2 back to Zone 1, visually distinguished from all standard REST arrows.

---

**ZONE 2: Application Layer — Spring Boot Backend (Deployed on Render Free Tier)**

Technology: Java 21, Spring Boot 3, embedded Tomcat, Apache PDFBox, LangChain4j, Maven.

Deployment note: The Render free tier instance sleeps after 15 minutes of inactivity. A cron job hosted on cron-job.org pings `GET /api/health` every 14 minutes to keep the container alive. The backend exposes CORS headers allowing traffic from the Vercel frontend domain.

This zone is divided into four layers: Middleware, Controllers, Services, and State Stores.

**Middleware Layer:**

*CORS Filter:* Configured via `@CrossOrigin(origins = "*")` on the controller, allowing the Vercel-hosted frontend to make cross-origin requests to the Render backend.

*RequestLoggingFilter:* A custom `OncePerRequestFilter` that intercepts every HTTP request and response. It logs the HTTP method, request URI, HTTP response status code, and exact processing latency in milliseconds. This provides full enterprise observability of every API call without any external monitoring tool.

*GlobalExceptionHandler:* A `@RestControllerAdvice` class that intercepts all exceptions thrown anywhere in the application and maps them to structured `ErrorResponse` JSON payloads with the correct HTTP status codes. It handles: `Exception.class` → 500 Internal Server Error, `IllegalArgumentException.class` → 400 Bad Request (used for invalid file type or empty file), `DocumentNotFoundException.class` → 404 Not Found (used when a deleted or nonexistent documentId is queried), `MethodArgumentNotValidException.class` → 400 Validation Error (triggered by `@NotBlank` on `QueryRequest`), `MaxUploadSizeExceededException.class` → 413 Payload Too Large, and `CapacityExceededException.class` → 429 Too Many Requests (triggered when active document count reaches 5).

**Controller Layer:**

*DocumentController:* The single REST controller exposing all document lifecycle endpoints. Each endpoint has a guardrail check via `documentService.verifyDocumentExists(id)` that throws a 404 before any AI call is made if the document ID is invalid or soft-deleted.

- `POST /api/documents/upload` — accepts a multipart PDF, delegates to DocumentService for ingestion, returns an `UploadResponse` containing the documentId, filename, and a success message with HTTP 201 Created.
- `GET /api/documents/{id}/summary` — delegates to MetadataExtractor for structured extraction, returns a `DocumentSummary` record.
- `POST /api/documents/{id}/query` — accepts a `QueryRequest` JSON body containing the user's question (validated with `@NotBlank`), delegates to RAGService, returns a `text/event-stream` `SseEmitter` for real-time token streaming.
- `DELETE /api/documents/{id}` — triggers the tri-layer deletion mechanism.
- `GET /api/documents/{id}/chat/history` — returns the stored `List<ChatMessage>` for that document session.
- `DELETE /api/documents/{id}/chat/reset` — clears chat history without removing the document from memory.

*HealthController:* A simple `GET /api/health` endpoint returning a JSON map with status "UP", service name, and a ready message. Used by the cron job.

**Service Layer:**

*DocumentService:* Responsible for the entire document ingestion pipeline. On upload, it validates that the file is non-empty and is a PDF (`application/pdf` content type check). It uses Apache PDFBox's `PDFTextStripper` to extract raw text from the PDF. It generates a UUID as the `documentId`. It stores the raw text in a `ConcurrentHashMap<String, String>` called `rawTextStore` keyed by documentId. It implements the **Capacity Guardrail**: before storing any new document, it checks if `rawTextStore.size() >= 5` and throws a `CapacityExceededException` (mapped to HTTP 429) if so, preventing Out-Of-Memory crashes on the 512MB Render free-tier container. It then creates a LangChain4j `Document` object with `Metadata` containing the `documentId` and original filename. It splits the document using a recursive `DocumentSplitter` configured for **1000 characters per chunk with 150-character overlap** — this is a deliberate optimization that reduces the number of chunks by over 60% compared to the original 500/50 configuration, significantly reducing Gemini Embedding API calls during ingestion. It embeds all chunks using the `EmbeddingModel` and stores both embeddings and text segments in the `EmbeddingStore`. It also exposes a `verifyDocumentExists(String id)` method that throws `DocumentNotFoundException` if the ID is not in `rawTextStore`, serving as a centralized guardrail called at the top of every controller endpoint before any AI processing begins.

*RAGService:* The core AI orchestration engine. It maintains three in-memory state maps: `summaryCache` (`ConcurrentHashMap<String, DocumentSummary>`) for caching generated metadata, `chatSessions` (`ConcurrentHashMap<String, List<ChatMessage>>`) for per-document conversation history, and `deletedDocumentIds` (`Set<String>`) for soft-delete tracking during vector retrieval.

For the streaming RAG query flow: First, it runs a **Prompt Injection Guardrail** — it checks the user's question against a list of known malicious patterns (e.g. "ignore previous instructions", "you are now", "forget your system prompt"). If detected, it immediately returns an HTTP 400 Bad Request without touching the LLM. Second, it embeds the user's question using the `EmbeddingModel`. Third, it performs a semantic vector search using an `EmbeddingSearchRequest` with `maxResults = 3` and a metadata filter on `documentId` — this isolation filter is critical for preventing cross-document context contamination when multiple documents are active simultaneously. It also filters out any chunk whose `documentId` is in the `deletedDocumentIds` set (soft-delete). Fourth, it assembles the top 3 retrieved `TextSegment` objects into a context string and extracts the highest `EmbeddingMatch.score()` as the confidence score. Fifth, it retrieves the last 6 messages (3 conversational turns) from `chatSessions` for that document and prepends them to the message list. Sixth, it constructs a system prompt that strictly instructs the LLM to answer only from the provided document context and to respond with "Information not found in document" if the answer cannot be found — this is the primary hallucination mitigation guardrail. Seventh, it calls `StreamingChatLanguageModel.generate()` and returns an `SseEmitter`. The `StreamingResponseHandler` first emits a metadata SSE event containing the confidence score and source text snippets, then streams individual tokens as they are generated by the model. A **Graceful Degradation** wrapper (`try-catch`) around the LLM call catches any Gemini API outage or rate-limit exception and falls back to returning the raw retrieved context chunks directly to the user as a plain-text response, ensuring the app remains functional even when the AI backend is unavailable.

The tri-layer deletion mechanism on `DELETE /api/documents/{id}` performs three operations in sequence: removes the entry from `rawTextStore` in DocumentService, removes the summary from `summaryCache` and the chat history from `chatSessions` in RAGService, and adds the `documentId` to the `deletedDocumentIds` set — since `InMemoryEmbeddingStore` in LangChain4j 0.36.x has no native delete-by-metadata API, this soft-delete set acts as a filter applied at query time to exclude chunks belonging to deleted documents.

**Data Models (Java Records):**
- `UploadResponse(String documentId, String fileName, String message)`
- `QueryRequest(@NotBlank String question)`
- `QueryResponse(String answer, String documentId, double confidenceScore, List<String> sourcesUsed)`
- `DocumentSummary(String documentType, String shortSummary, List<String> keyEntities)`
- `ErrorResponse(int status, String error, String message, LocalDateTime timestamp)`

---

**ZONE 3: AI & Data Layer — LangChain4j + Google Gemini API**

Technology: LangChain4j 0.36.2, Google Gemini API (`gemini-2.5-flash` for chat and extraction, `gemini-embedding-2` for vectorization), InMemoryEmbeddingStore, LangChain4j AiServices declarative interface.

All beans are configured in `LangChain4jConfig.java` as Spring `@Bean` definitions, injected via constructor injection throughout the service layer.

**Ingestion Pipeline (left-to-right flow):**
Raw text from PDFBox → `DocumentSplitter` (recursive, 1000/150) → `List<TextSegment>` with documentId metadata → `GoogleAiEmbeddingModel` (`gemini-embedding-2`) → `List<Embedding>` (float vectors) → `InMemoryEmbeddingStore.addAll()`. The `EmbeddingStore` is defined as an interface (`EmbeddingStore<TextSegment>`), meaning the underlying implementation (`InMemoryEmbeddingStore`) can be swapped for a production vector database like Pinecone or pgvector with zero changes to any service or controller code — this is the key modular architecture talking point.

**Declarative Metadata Extraction:**
`MetadataExtractor` is a Java interface annotated with LangChain4j's `@SystemMessage` and `@UserMessage`. It defines a single method `extractMetadata(String documentText)` that returns a `DocumentSummary` Java record. LangChain4j's `AiServices.create()` generates a runtime proxy that handles all prompt construction, LLM invocation, JSON parsing, and Java Record mapping automatically. The system message instructs the model to return strictly typed JSON with no conversational text or markdown formatting. The LLM used is `gemini-2.5-flash` with `temperature = 0.3` to minimize hallucination and force factual extraction. This is declarative AI orchestration — the developer defines the contract (input type, output type, system instructions), and the framework handles all implementation details.

**Streaming RAG Query Flow:**
`StreamingChatLanguageModel` (`GoogleAiGeminiStreamingChatModel`, `gemini-2.5-flash`, `temperature = 0.3`) receives an assembled message list containing: the system prompt (hallucination guardrail), the retrieved context chunks, the last 6 chat history messages, and the current user question. It returns a stream of tokens via `StreamingResponseHandler` callbacks (`onNext(String token)`, `onComplete()`, `onError(Throwable)`). Each `onNext` callback pushes the token into the `SseEmitter` as a plain text event. Before the token stream begins, a single metadata SSE event is sent containing the JSON-serialized confidence score and source snippets. On `onComplete`, the assembled full response is appended to the document's `chatSessions` list (after pruning to the last 6 messages). On `onError`, the graceful degradation fallback fires.

**LangChain4j Configuration Bean Summary:**
- `ChatLanguageModel` bean → `GoogleAiGeminiChatModel` (used by MetadataExtractor for synchronous structured extraction)
- `StreamingChatLanguageModel` bean → `GoogleAiGeminiStreamingChatModel` (used by RAGService for token streaming)
- `EmbeddingModel` bean → `GoogleAiEmbeddingModel` with `gemini-embedding-2`
- `EmbeddingStore<TextSegment>` bean → `InMemoryEmbeddingStore` (interface — swappable)
- `MetadataExtractor` bean → `AiServices.create(MetadataExtractor.class, chatLanguageModel)`

The Gemini API key is stored as an environment variable (`GEMINI_API_KEY`) injected via `@Value("${gemini.api.key}")` — never hardcoded. On Render it is set as a secure environment variable. In local development it is set in the Eclipse STS run configuration.

---

**Cross-Cutting Concerns:**

- **API key security:** Gemini API key stored exclusively in environment variables, never in source code or committed to GitHub.
- **File size limits:** `spring.servlet.multipart.max-file-size=15MB` and `max-request-size=15MB` configured in `application.properties`, enforced at the Spring layer before any service code runs.
- **CI/CD:** Both frontend and backend are connected to GitHub via webhook. A push to the main branch automatically triggers a Vercel redeploy (frontend) and a Render redeploy (backend).
- **Keep-alive:** A cron-job.org task pings `GET /api/health` every 14 minutes, staying just under Render's 15-minute idle sleep threshold, ensuring cold starts never occur during demos or recruiter reviews.
- **Port configuration:** `server.port=${PORT:8080}` allows Render to inject its dynamic port via environment variable while defaulting to 8080 locally.

---

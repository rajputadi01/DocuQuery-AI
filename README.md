# 📄 DocuQuery AI: Enterprise Document Intelligence

![Architecture Diagram](./architecture/diagram.png)

**DocuQuery AI** is a full-stack, stateful Retrieval-Augmented Generation (RAG) platform designed for high-performance document intelligence. It transforms unstructured PDFs into structured, auditable, and actionable insights using a modern AI-native stack.

🔗 **Live Demo:** https://documentquery.vercel.app/
⚙️ **API Health Status:** https://docuquery-api-idh9.onrender.com/api/health

---

## ✨ Key Features

* **Real-Time Token Streaming (SSE):** Chat responses stream instantly token-by-token (<500ms latency), mirroring the ChatGPT UX and eliminating blocking HTTP wait times.
* **Stateful RAG Memory:** Maintains isolated, multi-turn conversational histories for up to 5 concurrent documents with instant context switching.
* **Zero Hallucination UI:** Every AI answer is backed by full auditability, including a mathematical **Relevance Score** and an expandable widget displaying the exact raw PDF text chunks used as context.
* **Declarative Metadata Extraction:** Bypasses conversational AI to force the LLM to zero-shot extract strictly typed JSON (Document Classification, Executive Summary, Entities).
* **Enterprise Guardrails:** * **Capacity Limits:** Hard cap at 5 active documents to prevent Out-Of-Memory (OOM) crashes on restricted environments.
  * **Security:** Pre-processing validation layer intercepts and blocks Prompt Injection attempts before they reach the LLM.
  * **Graceful Degradation:** Falls back to returning raw vector context if the LLM API experiences an outage or rate limit.

---

## 🛠️ Technology Stack

### Client Tier (React UI)
* **Framework:** React, Vite
* **Networking:** Axios (REST), Native Fetch API (SSE Streams)
* **UI/UX:** Tailwind CSS, Lucide Icons, `react-markdown` for LLM formatting parsing
* **Deployment:** Vercel

### Application Layer (Spring Boot Backend)
* **Core:** Java 21, Spring Boot 3.4, Embedded Tomcat, Maven
* **Processing:** Apache PDFBox (Text Extraction)
* **Middleware:** Custom Request Logging Filter (Latency tracking), Global Exception Handler (`@RestControllerAdvice`)
* **Deployment:** Render (Free Tier) + Cron-Job.org Keep-alive

### AI & Data Layer (LangChain4j)
* **Orchestration:** LangChain4j 0.36.2
* **Models:** Google Gemini (`gemini-2.5-flash` for extraction/chat, `gemini-embedding-2` for vectors)
* **Vector Database:** `InMemoryEmbeddingStore` (Coded to an interface; production-ready to be swapped to `pgvector` or `Pinecone`).
* **Ingestion:** Optimized Recursive Chunking (1000 chars / 150 overlap)

---

## 🧠 Architectural Highlights

1. **Tri-Layer Soft Deletion:** When a document is deleted, it is removed from the raw text store, its chat history and summary caches are cleared, and its ID is added to a soft-delete filter to isolate it from future vector searches.
2. **Declarative AI:** Utilizes LangChain4j's `AiServices` interface to automatically map unstructured Gemini responses directly into Java Records.
3. **Optimized Ingestion:** By tuning the `DocumentSplitter` to 1000/150 overlapping characters, the system reduces Gemini Embedding API calls by over 60% compared to standard defaults.

---

## 🚦 Getting Started (Local Development)

### Prerequisites
* Java 21+
* Node.js 18+
* Google Gemini API Key

### 1. Backend Setup

# Navigate to the backend directory
cd docuquery-api

# Set your API key as an environment variable
export GEMINI_API_KEY="your_api_key_here"

# Run the Spring Boot application
mvn spring-boot:run

The backend will start on http://localhost:8080

2. Frontend Setup

# Navigate to the frontend directory
cd docuquery-ui

# Install dependencies
npm install

# Start the Vite development server
npm run dev

The frontend will start on http://localhost:5173

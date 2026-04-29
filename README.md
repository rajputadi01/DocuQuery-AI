\# DocuQuery AI: Enterprise RAG Intelligence



!\[Architecture Diagram](./architecture/diagram.png)



DocuQuery AI is a full-stack, stateful \*\*Retrieval-Augmented Generation (RAG)\*\* platform designed for high-performance document intelligence. It transforms unstructured PDFs into structured, auditable, and actionable insights using a modern AI-native stack.



\*\*Live Demo:\*\* https://documentquery.vercel.app/ 

\*\*API Status:\*\* https://docuquery-api-idh9.onrender.com/api/health



\---



\## 🛠️ The Tech Stack

\- \*\*Frontend:\*\* React, Vite, Server-Sent Events (SSE), Tailwind CSS, Lucide.

\- \*\*Backend:\*\* Java 21, Spring Boot 3.4, Apache PDFBox, Maven.

\- \*\*AI Orchestration:\*\* LangChain4j 0.36.2.

\- \*\*Models:\*\* Google Gemini 2.5 Flash (Chat \& Extraction), Gemini Embedding 2 (Vectors).

\- \*\*Deployment:\*\* Vercel (UI), Render (API), Cron-Job.org (Keep-alive).



\---



\## Key Architectural Features



\### 1. Real-Time Token Streaming (SSE)

Unlike standard RAG apps that freeze during processing, DocuQuery utilizes \*\*Server-Sent Events (SSE)\*\*.

\- \*\*Latency Optimization:\*\* Reduced perceived latency from \~6s to \*\*<500ms\*\* (Time to First Token).

\- \*\*Asynchronous Delivery:\*\* Metadata (scores/sources) is pushed first, followed by a character-by-character response stream.



\### 2. Multi-Layered Enterprise Guardrails

\- \*\*Capacity Guardrail:\*\* Prevents OOM crashes on restricted environments (Render Free Tier) by capping active memory to 5 concurrent documents.

\- \*\*Security Guardrail:\*\* Pre-processing validation layer to detect and block \*\*Prompt Injection\*\* patterns before they reach the LLM.

\- \*\*Hallucination Mitigation:\*\* Strict system prompting combined with a vector-search isolation filter to prevent cross-document context contamination.



\### 3. Full Auditability (Transparent AI)

Every AI response is accompanied by:

\- \*\*Relevance Score:\*\* The mathematical similarity percentage from the vector match.

\- \*\*Source Transparency:\*\* An expandable UI widget showing the exact raw PDF chunks used as context.



\### 4. Declarative AI Extraction

Leverages LangChain4j's `AiServices` to perform zero-shot structured data extraction, converting raw text into strictly typed JSON (Document Type, Summary, Entities) with zero conversational "noise."



\---



\## Deployment \& Scaling

The architecture is built on a \*\*Modular Interface Pattern\*\*:

\- The `EmbeddingStore` is currently an `InMemory` implementation for cost-efficiency.

\- \*\*Production Readiness:\*\* Because the service layer codes against interfaces, this can be swapped for \*\*pgvector\*\*, \*\*Pinecone\*\*, or \*\*Milvus\*\* with zero changes to the business logic.



\---



\## 🚦 Getting Started

1\. \*\*Clone the repo:\*\* `git clone https://github.com/your-username/DocuQuery-AI.git`

2\. \*\*Setup Backend:\*\* Add `GEMINI\_API\_KEY` to your environment variables.

3\. \*\*Run Backend:\*\* `mvn spring-boot:run` in `/docuquery-api`

4\. \*\*Run Frontend:\*\* `npm run dev` in `/docuquery-ui`


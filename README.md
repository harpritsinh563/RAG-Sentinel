# RAG-Observer-Sentinel 🛡️

> Traditional software either works, or it throws a stack trace. AI systems can return a perfectly healthy HTTP 200 OK while confidently hallucinating, violating data policies, and silently burning compute costs. 

**RAG-Observer-Sentinel** is a simple toy Retrieval-Augmented Generation (RAG) application built to demonstrate how Site Reliability Engineering (SRE) principles apply to AI. It moves beyond simple API wrappers by implementing strict observability (O11y), custom AI metrics, and input/output guardrails using OpenTelemetry.



---

## 📖 What This Is All About

This project serves as a blueprint for hosting, securing, and monitoring RAG based Generative AI applications. Instead of relying on black-box vendor metrics, this system intercepts, measures, and traces every step of the AI reasoning pipeline. 

It captures **AI Service Level Indicators (SLIs)** that matter:
* **Time to First Token (TTFT):** Measuring actual user-perceived latency.
* **Vector Search Latency:** Isolating database retrieval time from LLM inference time.
* **Token Economics:** Tracking hardware saturation and token throughput.
* **Guardrail Violations:** Intercepting and logging malicious prompt injections before they hit the model.

---

## 🛠️ The Technology Stack

This application is built entirely on a localized, cost-free open-source stack to ensure complete operational ownership and data privacy.

### The Application Layer
* **Java 19 & Spring Boot 3.x:** The robust, enterprise standard for backend routing and dependency injection.
* **Spring AI:** The orchestration framework used to abstract the complexities of connecting the LLMs to the Vector Database.

### The AI & Data Layer
* **Ollama (`phi3`):** A lightweight, highly capable local LLM handling the text generation (inference).
* **Ollama (`nomic-embed-text`):** A specialized, rapid embedding model to convert document text into vector numbers.
* **PgVector (PostgreSQL):** The vector database storing company documents and performing high-speed mathematical similarity searches.

### The Observability (O11y) Pipeline
* **Micrometer Tracing & OpenTelemetry (OTel):** The telemetry standard used to auto-instrument the Java application and emit custom metrics.
* **OTel Collector:** The central routing hub that receives data from the Spring Boot app.
* **Jaeger:** Visualizes distributed traces (the "waterfall" graph of the request lifecycle).
* **Prometheus:** A time-series database storing the numerical metric data.
* **Grafana:** The UI used to build real-time monitoring dashboards.

---

## Architecture & Flow
![Architecture](/docs/RAG_Observer_Sentinel_Architecture.png)


---

## 🚀 How to Start on Local Machine

Follow these exact steps to spin up the entire infrastructure and application locally.

### 1. Prerequisites
Ensure you have the following installed on your machine:
* Java 19
* Maven
* Docker Desktop (with WSL 2 enabled if on Windows)
* Ollama (Download from ollama.com)

### 2. Pull the Local AI Models
Open your terminal and pull the required open-source models into Ollama.
```bash
# Pull the generation model
ollama run phi3

# Open a new terminal tab and pull the embedding model
ollama run nomic-embed-text

# Start the docker images for Prometheus, Graffana, OTel Collector, Jaegger, pgVector
cd observability
docker-compose up -d
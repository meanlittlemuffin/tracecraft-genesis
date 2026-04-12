# Project Generation Prompts

This file contains prompts that can be used to generate this Session Replay Tool project using AI coding assistants.

## Full Project Generation

### Main Prompt - Complete Project

```
Create a Session Replay Tool with the following components:

1. Chrome Extension (Manifest V3)
   - popup.html/popup.css/popup.js for UI
   - content-script.js injected into all pages
   - Records: clicks, network calls (fetch/XHR), console logs, JavaScript errors
   - Uses chrome.storage.local for storage
   - Buttons: Start Recording, Stop Recording, Test Connection, Export JSON, Send to API, Clear

2. Spring Boot Backend (Java 15)
   - REST API with endpoints: /api/analyze, /api/report, /api/root-cause, /api/reproduce, /api/health
   - Uses completions.me API (OpenAI-compatible) with Claude Opus 4.6 model
   - Configuration via application.yml
   - Service class: AIService

Project structure:
session-replay-tool/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hackathon/sessionreplay/
│       │   ├── SessionReplayApplication.java
│       │   ├── api/RecordingController.java
│       │   └── service/AIService.java
│       └── resources/application.yml
├── browser-extension/
│   ├── manifest.json
│   ├── popup/ (popup.html, popup.css, popup.js)
│   └── src/ (content-script.js, background.js)

Use completions.me API at https://completions.me/api/v1 with model claude-opus-4.6
```

---

## Component-Specific Prompts

### Backend - Spring Boot Setup

```
Create a Spring Boot 2.7.18 backend with:
- pom.xml with spring-boot-starter-web, jackson dependencies
- Java version 15
- Main application class at com.hackathon.sessionreplay.SessionReplayApplication
- REST controller at com.hackathon.sessionreplay.api.RecordingController
- Service class at com.hackathon.sessionreplay.service.AIService

Endpoints to implement:
- GET /api/health - returns {"status": "UP"}
- POST /api/analyze - accepts recording JSON, returns {"analysis": "..."}
- POST /api/report - generates bug report
- POST /api/root-cause - performs root cause analysis
- POST /api/reproduce - generates reproduction steps (curl commands)

Use completions.me API (OpenAI-compatible) with base-url https://completions.me/api/v1
Model: claude-opus-4.6
Configuration in application.yml
```

### Chrome Extension - Manifest and Popup

```
Create a Chrome Extension (Manifest V3) with:

manifest.json:
- name: "Session Replay Tool"
- version: "1.0"
- permissions: ["activeTab", "storage", "downloads"]
- host_permissions: ["<all_urls>"]
- action: default_popup: "popup/popup.html"
- content_scripts: matches ["<all_urls>"], js: ["src/content-script.js"]

popup/popup.html:
- Buttons: Start Recording, Stop Recording, Test Connection, Export JSON, Send to API, Clear
- Status display area

popup/popup.css:
- Basic styling for the popup UI

popup/popup.js:
- Communicate with content-script via chrome.tabs.sendMessage
- Store state in chrome.storage.local
- Handle button clicks and API calls
```

### Content Script

```
Create content-script.js that:
- Runs at document_start on all pages
- Listens for messages: startRecording, stopRecording, testConnection
- Captures: click events, fetch/XHR network calls, console logs, JavaScript errors
- Stores events in array
- Returns recorded data on stopRecording message
- Message format: {type: "click"|"network"|"console"|"error", ...}
```

### AI Service Integration

```
Create AIService.java that:
- Uses Spring's RestTemplate
- Makes POST calls to completions.me API endpoint: https://completions.me/api/v1/chat/completions
- Request format (OpenAI-compatible):
  {
    "model": "claude-opus-4.6",
    "max_tokens": 2048,
    "messages": [{"role": "user", "content": "..."}]
  }
- Authorization: Bearer token in header
- Response parsing from choices[0].message.content

Prompts to send:
1. analyzeRecording: Summarize recording, list key events, identify issues
2. generateReport: Bug report with issue description, timeline, network summary, console logs, reproduction steps
3. analyzeRootCause: Primary root cause with confidence %, secondary factors, evidence
4. generateReproductionSteps: curl commands for API calls, headers, body, expected vs actual
```

---

## Testing Prompts

### Test Backend

```
Test the backend endpoints with curl:

# Health check
curl http://localhost:8080/api/health

# Analyze recording
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"events":[{"type":"click","selector":"#btn"},{"type":"error","message":"TypeError"}],"url":"http://example.com"}'
```

### Test Chrome Extension

```
1. Go to chrome://extensions/
2. Enable Developer mode
3. Click "Load unpacked" - select browser-extension folder
4. Click extension icon in toolbar
5. Click "Start Recording"
6. Interact with a webpage
7. Click "Stop Recording"
8. Click "Export JSON" to download
```

---

## Configuration Prompts

### Environment Setup

```
Setup required:
- Java 15 (JAVA_HOME set)
- Maven 3.9.6
- Chrome browser

application.yml content:
server:
  port: 8080

spring:
  application:
    name: session-replay

completions:
  api-key: YOUR_COMPLETIONS_ME_API_KEY
  base-url: https://completions.me/api/v1
  model: claude-opus-4.6

logging:
  level:
    root: INFO
```

---

## Alternative AI Providers

To switch to different free AI providers:

### Scitely
```yaml
completions:
  api-key: YOUR_SCITELY_KEY
  base-url: https://api.scitely.com/v1
  model: deepseek-r1
```

### Groq
```yaml
completions:
  api-key: YOUR_GROQ_KEY
  base-url: https://api.groq.com/openai/v1
  model: llama-3.3-70b-versatile
```
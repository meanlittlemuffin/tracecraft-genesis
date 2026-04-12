# TraceCraft Genesis

A browser session replay tool that records everything a user does — clicks, network calls with full request/response payloads, console logs, errors, performance metrics — and uses AI to analyze recordings for bugs, network bottlenecks, and session health.

## Architecture

```
Chrome Extension                          Spring Boot 3.3.5 Backend
┌──────────────────────┐                  ┌──────────────────────────┐
│ MAIN world           │                  │ RecordingController      │
│  page-interceptor.js │                  │  POST /api/analyze       │
│  (fetch, XHR,        │   postMessage    │  POST /api/network-      │
│   console, errors)   │──────────────┐   │       bottlenecks        │
├──────────────────────┤              │   │  POST /api/bug-diagnosis │
│ ISOLATED world       │              │   │  GET  /api/health        │
│  content-script.js   │──────────────┤   ├──────────────────────────┤
│  (clicks, keyboard,  │              │   │ AIService                │
│   scroll, Web Vitals)│  HTTP POST   │   │  (Spring AI ChatClient)  │
├──────────────────────┤──────────────┘──▶├──────────────────────────┤
│ Popup UI             │                  │ Google Gemini 2.5 Flash  │
│  (popup.html/js/css) │                  │ (free tier)              │
└──────────────────────┘                  └──────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Spring Boot 3.3.5, Spring AI 1.0.0, Java 17+ |
| AI Model | Google Gemini 2.5 Flash (free tier via OpenAI-compatible endpoint) |
| Extension | Chrome Manifest V3 with MAIN + ISOLATED world scripts |
| Build | Maven 3.9.6 (via wrapper) |

## Setup

### 1. Get a Gemini API Key (free)

1. Go to https://aistudio.google.com
2. Click "Get API key" → "Create API key"
3. Copy the key

### 2. Configure and Start the Backend

```bash
cd backend

# Edit src/main/resources/application.yml and paste your Gemini API key

# Build and run
./mvnw spring-boot:run
```

Verify: `curl http://localhost:8080/api/health` should return `{"status":"UP"}`

### 3. Load the Chrome Extension

1. Go to `chrome://extensions/`
2. Enable "Developer mode" (top right toggle)
3. Click "Load unpacked" → select the `browser-extension/` folder
4. The extension icon appears in your toolbar

## Usage

### Recording a Session

1. Click the extension icon
2. Click **Start Recording**
3. Browse the website — interact, trigger the bug, etc.
4. Click **Stop Recording**

### Analyzing

| Button | What it does |
|--------|-------------|
| **Export JSON** | Download raw recording (no backend needed) |
| **Full Analysis** | Broad session health: score, issues, network report, UX report, recommendations |
| **Network Bottlenecks** | Slow endpoints, N+1 patterns, redundant calls, CORS overhead, compression |
| **Bug Diagnosis** | Root cause + trigger chain, bug report with timeline, reproduction steps with curl commands |
| **Clear** | Reset recording data |

Wait ~30 seconds between analysis buttons to avoid Gemini free tier rate limits.

## What Gets Recorded

- **Network calls**: Full URL, method, headers, request body, response body, status, duration, query params (for both fetch and XHR)
- **Clicks**: Position, target element details, CSS selector, bounding rect
- **Console**: All levels (log, info, warn, error, debug)
- **Errors**: JS errors with stack traces, unhandled promise rejections, CSP violations
- **Navigation**: SPA route changes (pushState, replaceState, popstate)
- **Keyboard**: Key events (password fields automatically redacted)
- **Performance**: Web Vitals (LCP, CLS, INP, TTFB), long tasks, resource timings, memory snapshots

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/analyze` | POST | Full session analysis with structured JSON response |
| `/api/network-bottlenecks` | POST | Network performance analysis |
| `/api/bug-diagnosis` | POST | Root cause, bug report, and reproduction steps |

## Test Websites

| Website | Good for testing |
|---------|-----------------|
| https://the-internet.herokuapp.com | JS errors, broken images, failed logins |
| https://reqres.in | Live API calls with visible request/response |
| https://automationexercise.com | E-commerce with lots of network + interaction data |

## Project Structure

```
tracecraft-genesis/
├── backend/
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/
│       ├── java/com/hackathon/sessionreplay/
│       │   ├── SessionReplayApplication.java
│       │   ├── api/RecordingController.java
│       │   ├── model/AnalysisModels.java
│       │   └── service/AIService.java
│       └── resources/application.yml
├── browser-extension/
│   ├── manifest.json
│   ├── popup/ (popup.html, popup.css, popup.js)
│   └── src/ (page-interceptor.js, content-script.js, background.js)
├── AGENTS.md                         # Detailed developer documentation (for AI tools)
└── docs/
    ├── HACKATHON_PLAN.md             # 12-hour build plan with gotchas
    └── TraceCraft Prompts.md         # Prompts to recreate the project with any AI tool
```

## License

MIT

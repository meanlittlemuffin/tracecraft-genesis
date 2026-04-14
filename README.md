# TraceCraft Genesis

A browser session replay tool that records everything a user does â€” clicks, network calls with full request/response payloads, console logs, errors, performance metrics â€” and uses AI to analyze recordings for bugs, network bottlenecks, and session health.

## Architecture

```
Chrome Extension                          Spring Boot 3.3.5 Backend
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ MAIN world           â”‚                  â”‚ RecordingController      â”‚
â”‚  page-interceptor.js â”‚                  â”‚  POST /api/analyze       â”‚
â”‚  (fetch, XHR,        â”‚   postMessage    â”‚  POST /api/network-      â”‚
â”‚   console, errors)   â”‚â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   â”‚       bottlenecks        â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤              â”‚   â”‚  POST /api/bug-diagnosis â”‚
â”‚ ISOLATED world       â”‚              â”‚   â”‚  GET  /api/health        â”‚
â”‚  content-script.js   â”‚â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤   â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  (clicks, keyboard,  â”‚              â”‚   â”‚ AIService                â”‚
â”‚   scroll, Web Vitals)â”‚  HTTP POST   â”‚   â”‚  (Spring AI ChatClient)  â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜â”€â”€â–¶â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ Popup UI             â”‚                  â”‚ Google Gemini 2.5 Flash  â”‚
â”‚  (popup.html/js/css) â”‚                  â”‚ (free tier)              â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

### Local Demo App + Server Log RCA Flow

For localhost demos, TraceCraft now augments browser recordings with server logs from the in-repo demo app.

1. The Chrome extension records browser events and sends the recording to backend analysis endpoints.
2. `ServerLogService` reads the configured demo-app log file when the recorded host is `localhost` or `127.0.0.1`.
3. `IncidentPacketService` builds a compact, high-signal incident packet (browser evidence + correlated server log excerpts).
4. `AIService` sends this compact packet to Gemini with cooldown + caching to reduce free-tier rate-limit issues.
5. `Bug Diagnosis` returns structured RCA and includes `serverLogSummary` so you can see whether server logs were used.

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
2. Click "Get API key" â†’ "Create API key"
3. Copy the key

### 2. Configure and Start the Backend

```bash
cd backend

# Edit src/main/resources/application.yml and paste your Gemini API key

# Build and run
./mvnw spring-boot:run
```

Verify: `curl http://localhost:8080/api/health` should return `{"status":"UP"}`

### 3. Open the Dashboard

Once the backend is running, open the dashboard in your browser at:

`http://localhost:8080/dashboard.html`

The dashboard loads session summaries from:

- `http://localhost:8080/api/dashboard/sessions`
- `http://localhost:8080/api/dashboard/session/{fileName}`

### 4. Run the Local Demo App (for server-log-assisted RCA)

Use the in-repo demo app to generate deterministic failures and server logs:

```bash
cd demo-app
../backend/mvnw spring-boot:run
```

Demo app:
- `http://localhost:8090`

Detailed guide:
- `demo-app/README.md`

### 5. Load the Chrome Extension

1. Go to `chrome://extensions/`
2. Enable "Developer mode" (top right toggle)
3. Click "Load unpacked" â†’ select the `browser-extension/` folder
4. The extension icon appears in your toolbar

## Usage

### Recording a Session

1. Click the extension icon
2. Click **Start Recording**
3. Browse the website â€” interact, trigger the bug, etc.
4. Click **Stop Recording**

### Analyzing

| Button | What it does |
|--------|-------------|
| **Export JSON** | Download raw recording (no backend needed) |
| **Full Analysis** | Broad session health: score, issues, network report, UX report, recommendations |
| **Network Bottlenecks** | Slow endpoints, N+1 patterns, redundant calls, CORS overhead, compression |
| **Bug Diagnosis** | Root cause + trigger chain, bug report with timeline, reproduction steps with curl commands, plus `serverLogSummary` when server logs are available |
| **Clear** | Reset recording data |

Wait ~30 seconds between analysis buttons to avoid Gemini free tier rate limits.

### Dashboard

The backend also serves a dashboard UI for browsing saved session outputs.

- Open `http://localhost:8080/dashboard.html` in a browser
- The page fetches session data from `/api/dashboard/sessions`
- Clicking a session opens the full JSON from `/api/dashboard/session/{fileName}`

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
| `/api/dashboard/sessions` | GET | List saved dashboard session summaries |
| `/api/dashboard/session/{fileName}` | GET | Load a specific saved session JSON |

## Test Websites

| Website | Good for testing |
|---------|-----------------|
| https://the-internet.herokuapp.com | JS errors, broken images, failed logins |
| https://reqres.in | Live API calls with visible request/response |
| https://automationexercise.com | E-commerce with lots of network + interaction data |

## Project Structure

```
tracecraft-genesis/
|-- demo-app/
|   |-- README.md
|   |-- pom.xml
|   `-- src/main/
|       |-- java/com/hackathon/demo/
|       `-- resources/
|-- backend/
|   |-- pom.xml
|   |-- mvnw / mvnw.cmd
|   `-- src/main/
|       |-- java/com/hackathon/sessionreplay/
|       |   |-- SessionReplayApplication.java
|       |   |-- api/RecordingController.java
|       |   |-- model/AnalysisModels.java
|       |   `-- service/
|       |       |-- AIService.java
|       |       |-- IncidentPacketService.java
|       |       `-- ServerLogService.java
|       `-- resources/application.yml
|-- browser-extension/
|   |-- manifest.json
|   |-- popup/ (popup.html, popup.css, popup.js)
|   `-- src/ (page-interceptor.js, content-script.js, background.js)
|-- AGENTS.md
`-- docs/
    |-- HACKATHON_PLAN.md
    |-- SERVER_LOG_ASSISTED_RCA_PLAN.md
    `-- TraceCraft Prompts.md
```

## License

MIT

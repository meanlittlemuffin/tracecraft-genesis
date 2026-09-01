# TraceCraft Genesis

TraceCraft Genesis is a hackathon browser session replay prototype. It records what a user does in the browser, captures high-signal debugging evidence such as network calls, console logs, errors, and performance signals, then uses AI to analyze the session for bugs, bottlenecks, and likely root causes.

For localhost demos, it can also correlate the browser recording with server logs from the in-repo `demo-app`. After a successful bug diagnosis, the prototype can now suggest a likely code fix for the matched `demo-app` route by inspecting a small set of relevant Java source snippets.

## Architecture

```text
Chrome Extension                                   Spring Boot Backend
+----------------------------------+               +----------------------------------+
| MAIN world                       |               | RecordingController              |
| - page-interceptor.js            |               | - POST /api/analyze             |
| - fetch / XHR interception       | postMessage   | - POST /api/network-bottlenecks |
| - console + error capture        +-------------> | - POST /api/bug-diagnosis       |
+----------------------------------+               | - POST /api/code-fix-suggestion |
                                                   | - GET  /api/health              |
+----------------------------------+               +----------------+-----------------+
| ISOLATED world                   |                                |
| - content-script.js              |            HTTP POST           |
| - clicks / keyboard / scroll     +------------------------------> |
| - Web Vitals + storage           |                                v
+----------------------------------+               +----------------------------------+
                                                   | AIService                         |
                                                   | - incident packet analysis        |
                                                   | - bug diagnosis                   |
                                                   | - code fix suggestion             |
                                                   +----------------+-----------------+
                                                                    |
                                                                    v
                                                   +----------------------------------+
                                                   | Supporting services              |
                                                   | - IncidentPacketService          |
                                                   | - ServerLogService               |
                                                   | - CodeContextService             |
                                                   +----------------+-----------------+
                                                                    |
                                                                    v
                                                   +----------------------------------+
                                                   | Gemini 2.5 Flash                 |
                                                   | via OpenAI-compatible endpoint   |
                                                   +----------------------------------+
```

## Local Demo Flows

### Browser + Server Log RCA Flow

For localhost demos, TraceCraft augments browser recordings with server logs from the in-repo demo app.

1. The Chrome extension records browser events and sends the recording to backend analysis endpoints.
2. `ServerLogService` reads the configured `demo-app` log file when the recorded host is `localhost` or `127.0.0.1`.
3. `IncidentPacketService` builds a compact, high-signal incident packet from browser evidence and correlated server log excerpts.
4. `AIService` sends that packet to Gemini with caching and cooldown protection.
5. `Bug Diagnosis` returns a structured RCA and includes `serverLogSummary`.

### Code Fix Suggestion Flow

After a successful bug diagnosis, the popup enables `Suggest Code Fix`.

1. The extension sends the same recording payload to `POST /api/code-fix-suggestion`.
2. The backend reruns or reuses diagnosis evidence for the current incident.
3. `CodeContextService` scans `demo-app/src/main/java` and retrieves the most relevant controller or handler snippets using route matches and server-log class hints.
4. `AIService` combines diagnosis evidence, server-log evidence, and retrieved code snippets.
5. The API returns a structured `CodeFixSuggestion` response with impacted files, reasoning, before/after snippets, and validation steps.

This is intentionally a manual-review suggestion, not an auto-applied patch.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Backend | Spring Boot 3.3.5, Spring AI 1.0.0, Java 17+ |
| AI Model | Google Gemini 2.5 Flash via OpenAI-compatible endpoint |
| Extension | Chrome Manifest V3 with MAIN and ISOLATED world scripts |
| Build | Maven 3.9.6 via wrapper |
| Demo app | Spring Boot demo service on localhost |

## Setup

### 1. Get a Gemini API key

1. Go to [aistudio.google.com](https://aistudio.google.com)
2. Click `Get API key` -> `Create API key`
3. Copy the key

### 2. Configure and start the backend

```bash
cd backend

# Edit src/main/resources/application.yml and paste your Gemini API key

./mvnw spring-boot:run
```

Verify:

```bash
curl http://localhost:8080/api/health
```

Expected response:

```json
{"status":"UP"}
```

### 3. Open the dashboard

Once the backend is running, open:

`http://localhost:8080/dashboard.html`

The dashboard loads session summaries from:

- `http://localhost:8080/api/dashboard/sessions`
- `http://localhost:8080/api/dashboard/session/{fileName}`

### 4. Run the local demo app

Use the in-repo demo app to generate deterministic failures and server logs:

```bash
cd demo-app
../backend/mvnw spring-boot:run
```

Demo app:

- `http://localhost:8090`

Detailed guide:

- `demo-app/README.md`

### 5. Load the Chrome extension

1. Open `chrome://extensions/`
2. Enable `Developer mode`
3. Click `Load unpacked` and select the `browser-extension/` folder
4. Pin the extension if you want quicker access during demos

## Usage

### Recording a session

1. Click the extension icon
2. Click `Start Recording`
3. Interact with the target page and trigger the bug
4. Click `Stop Recording`

### Analysis actions

| Button | What it does |
|--------|---------------|
| `Export JSON` | Downloads the raw recording without calling the backend |
| `Full Analysis` | Returns broad session health, issue summaries, network findings, and UX recommendations |
| `Network Bottlenecks` | Focuses on slow endpoints, redundant calls, sequencing, compression, and related network issues |
| `Bug Diagnosis` | Returns root cause, trigger chain, bug report, reproduction steps, and `serverLogSummary` when server logs were used |
| `Suggest Code Fix` | Available only after a successful bug diagnosis; returns a likely `demo-app` code fix suggestion with impacted files and before/after snippets |
| `Clear` | Resets the current recording and popup state |

Note:

- Wait about 30 seconds between unrelated AI analysis actions to avoid Gemini free-tier cooldown limits.
- `Suggest Code Fix` is designed for the in-repo localhost `demo-app` only in this prototype.

### Dashboard

The backend also serves a dashboard UI for browsing saved session outputs.

- Open `http://localhost:8080/dashboard.html`
- The page fetches session data from `/api/dashboard/sessions`
- Clicking a session opens the full JSON from `/api/dashboard/session/{fileName}`

## What Gets Recorded

- Network calls: full URL, method, headers, request body, response body, status, duration, and query params for both `fetch` and XHR
- Clicks: position, target element details, CSS selector, and bounding rect
- Console logs: `log`, `info`, `warn`, `error`, and `debug`
- Errors: JavaScript errors with stack traces, unhandled promise rejections, and CSP violations
- Navigation: SPA route changes via `pushState`, `replaceState`, and `popstate`
- Keyboard activity: key events with sensitive fields redacted
- Performance data: Web Vitals, long tasks, resource timings, and memory snapshots

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/analyze` | POST | Full session analysis with structured JSON response |
| `/api/network-bottlenecks` | POST | Network performance analysis |
| `/api/bug-diagnosis` | POST | Root cause, bug report, reproduction steps, and server-log-assisted RCA |
| `/api/code-fix-suggestion` | POST | Demo-app code fix suggestion using diagnosis evidence plus matched source snippets |
| `/api/dashboard/sessions` | GET | List saved dashboard session summaries |
| `/api/dashboard/session/{fileName}` | GET | Load a specific saved session JSON |

## Demo App Scope for Code Fix Suggestions

The new code-fix feature is intentionally narrow in v1.

- Supported scope: localhost sessions against the in-repo `demo-app`
- Source root scanned by default: `demo-app/src/main/java`
- Matching strategy: route annotations, server-log class hints, and compact snippet retrieval
- Output: suggested manual fix only
- Not supported: automatic edits, patch application, or arbitrary external repos

Related backend config in `backend/src/main/resources/application.yml`:

```yaml
tracecraft:
  code-context:
    enabled: true
    source-root: ${user.dir}/../demo-app/src/main/java
    max-files: 3
    max-snippet-lines: 50
```

## Test Websites

| Website | Good for testing |
|---------|------------------|
| [the-internet.herokuapp.com](https://the-internet.herokuapp.com) | JavaScript errors, broken images, failed logins |
| [reqres.in](https://reqres.in) | Live API calls with visible request and response behavior |
| [automationexercise.com](https://automationexercise.com) | E-commerce interactions with lots of network and UI activity |

## Project Structure

```text
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
|       |   |-- config/TracecraftProperties.java
|       |   |-- model/AnalysisModels.java
|       |   `-- service/
|       |       |-- AIService.java
|       |       |-- CodeContextService.java
|       |       |-- IncidentPacketService.java
|       |       `-- ServerLogService.java
|       `-- resources/application.yml
|-- browser-extension/
|   |-- manifest.json
|   |-- popup/
|   |   |-- popup.html
|   |   |-- popup.css
|   |   `-- popup.js
|   `-- src/
|       |-- background.js
|       |-- content-script.js
|       `-- page-interceptor.js
|-- CODE_FIX_SUGGESTION_PLAN
|-- AGENTS.md
`-- docs/
    |-- HACKATHON_PLAN.md
    |-- SERVER_LOG_ASSISTED_RCA_PLAN.md
    `-- TRACECRAFT_PROMPTS.md
```

## License

MIT

# TraceCraft Genesis - Developer Documentation

## Project Overview

TraceCraft Genesis is a browser session replay and AI analysis tool. It records everything a user does in the browser — clicks, network calls (with full request/response payloads), console logs, errors, navigation, keyboard input, scroll behavior, and performance metrics — then sends the recording to a Spring Boot backend where Google Gemini 2.5 Flash analyzes it for bugs, network bottlenecks, and session health.

### Key Capabilities

- Records comprehensive browser session data (network calls are the most detailed)
- Full request/response capture: URL, method, headers, body, query params, status, timing
- Performance metrics: Web Vitals (LCP, CLS, INP, TTFB), long tasks, memory snapshots
- User interactions: clicks, keyboard (privacy-redacted), scroll, form submissions, SPA navigation
- AI-powered analysis via Google Gemini 2.5 Flash (free tier)
- Bug diagnosis: root cause analysis, bug report generation, and reproduction steps in one click
- Network bottleneck detection: slow endpoints, N+1 patterns, redundant calls, CORS overhead

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Chrome Browser                              │
│                                                                      │
│  ┌──────────────────┐    ┌──────────────────────────────────────┐   │
│  │  Popup UI        │    │  MAIN World (page-interceptor.js)    │   │
│  │  (popup.html/    │    │  Intercepts: fetch, XHR, console,   │   │
│  │   popup.js)      │    │  errors, SPA navigation, memory     │   │
│  └────────┬─────────┘    └──────────────┬───────────────────────┘   │
│           │                             │ window.postMessage         │
│           │ chrome.tabs.sendMessage     ▼                           │
│           │                  ┌──────────────────────────────────┐   │
│           └─────────────────▶│  ISOLATED World (content-script) │   │
│                              │  Captures: clicks, keyboard,     │   │
│                              │  scroll, forms, Web Vitals,      │   │
│                              │  long tasks, perf timings        │   │
│                              │  Stores all data in              │   │
│                              │  chrome.storage.local            │   │
│                              └──────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Background Service Worker (background.js)                    │   │
│  │  chrome.webRequest: resource metadata, from-cache, server IP  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
                    │
                    │ HTTP POST /api/analyze, /api/network-bottlenecks,
                    │           /api/bug-diagnosis
                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Spring Boot 3.3.5 Backend                        │
│                                                                      │
│  ┌──────────────────┐    ┌──────────────────────────────────────┐   │
│  │  Recording       │───▶│  AIService                           │   │
│  │  Controller      │    │  (Spring AI ChatClient)              │   │
│  │  /api/*          │    │                                      │   │
│  └──────────────────┘    └──────────────┬───────────────────────┘   │
│                                         │                           │
│                                         ▼                           │
│                    ┌─────────────────────────────────────┐          │
│                    │  Google Gemini 2.5 Flash (Free)     │          │
│                    │  via OpenAI-compatible endpoint      │          │
│                    │  generativelanguage.googleapis.com   │          │
│                    └─────────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
tracecraft-genesis/
├── backend/                              # Spring Boot 3.3.5 Backend
│   ├── pom.xml                           # Maven: Spring Boot 3.3.5, Spring AI 1.0.0, Java 17
│   ├── mvnw / mvnw.cmd                  # Maven wrapper scripts
│   ├── .mvn/wrapper/                     # Maven wrapper jar + properties
│   └── src/main/
│       ├── java/com/hackathon/sessionreplay/
│       │   ├── SessionReplayApplication.java    # Main Spring Boot app
│       │   ├── api/
│       │   │   └── RecordingController.java     # REST endpoints (4 endpoints)
│       │   ├── model/
│       │   │   └── AnalysisModels.java          # Java records for AI response parsing
│       │   └── service/
│       │       └── AIService.java               # Spring AI ChatClient integration
│       └── resources/
│           └── application.yml                  # Config: Gemini API key, model, server port
│
├── browser-extension/                    # Chrome Extension (Manifest V3)
│   ├── manifest.json                     # MV3 manifest with MAIN + ISOLATED world scripts
│   ├── icons/                           # Extension icons (SVG: 16, 48, 128)
│   ├── popup/
│   │   ├── popup.html                   # Extension popup UI
│   │   ├── popup.css                    # Dark theme styles
│   │   └── popup.js                     # UI logic, payload building, API calls
│   └── src/
│       ├── page-interceptor.js          # MAIN world: fetch/XHR/console/error interception
│       ├── content-script.js            # ISOLATED world: clicks, keyboard, scroll, perf, storage
│       └── background.js               # Service worker: chrome.webRequest metadata
│
├── AGENTS.md                            # This file (developer docs for AI tools)
├── README.md                            # User documentation
└── .gitignore
```

## Backend

### Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 3.3.5 | Web framework |
| Spring AI | 1.0.0 | AI integration (ChatClient, prompt templates) |
| Java | 17+ (runs on 22) | Language |
| Maven | 3.9.6 (via wrapper) | Build tool |
| Jackson | (managed by Boot) | JSON serialization |
| Gemini 2.5 Flash | Free tier | AI model for analysis |

### API Endpoints

| Endpoint | Method | Description | Response Type |
|----------|--------|-------------|---------------|
| `/api/health` | GET | Health check | `{"status": "UP"}` |
| `/api/analyze` | POST | Full session analysis: health score, issues, network report, UX report, recommendations | `SessionAnalysis` JSON |
| `/api/network-bottlenecks` | POST | Network-focused: slow endpoints, N+1 patterns, redundant calls, CORS, compression | `NetworkBottleneckReport` JSON |
| `/api/bug-diagnosis` | POST | Bug investigation: root cause with trigger chain, bug report, reproduction steps with curl commands | `BugDiagnosis` JSON |

### How AI Integration Works

1. **Controller** receives recording as `Map<String, Object>`
2. **AIService** serializes it to JSON (truncated to 80K chars to fit Gemini's free tier token limits)
3. **Spring AI ChatClient** sends the data to Gemini with a system prompt (role) and user prompt (schema + data)
4. Gemini returns a text response (JSON, sometimes wrapped in markdown code fences)
5. **AIService.parseResponse()** strips any markdown fences, then parses with Jackson
6. All record fields use `Object` type to tolerate Gemini's inconsistent output (may return numbers as strings, arrays as counts, etc.)
7. **Controller** returns the parsed response, or a readable error message if AI fails (rate limit, invalid JSON)

### Request Format (sent by the extension)

```json
{
  "events": [
    {
      "type": "click",
      "timestamp": 1712900000000,
      "selector": "button#submit",
      "element": "BUTTON#submit",
      "text": "Submit Order",
      "pageUrl": "https://example.com/checkout"
    },
    {
      "type": "network",
      "timestamp": 1712900001000,
      "method": "POST",
      "url": "https://api.example.com/orders",
      "status": 500,
      "statusText": "Internal Server Error",
      "duration": 1240,
      "requestHeaders": { "content-type": "application/json", "authorization": "Bearer ey...[REDACTED]" },
      "requestBody": "{\"items\":[{\"id\":123}]}",
      "responseHeaders": { "content-type": "application/json" },
      "responseBody": "{\"error\":\"database connection failed\"}",
      "responseSize": 42,
      "initiatorType": "fetch",
      "pageUrl": "https://example.com/checkout"
    },
    {
      "type": "error",
      "timestamp": 1712900002000,
      "errorType": "uncaught",
      "message": "TypeError: Cannot read property 'orderId' of undefined",
      "stack": "at handleResponse (checkout.js:42:15)",
      "pageUrl": "https://example.com/checkout"
    },
    {
      "type": "console",
      "timestamp": 1712900001500,
      "level": "error",
      "message": "Order submission failed",
      "pageUrl": "https://example.com/checkout"
    },
    {
      "type": "navigation",
      "timestamp": 1712900003000,
      "navigationType": "pushState",
      "url": "/error",
      "pageUrl": "https://example.com/checkout"
    }
  ],
  "url": "https://example.com/checkout",
  "userAgent": "Mozilla/5.0...",
  "metadata": {
    "startTime": 1712899990000,
    "stopTime": 1712900010000,
    "startUrl": "https://example.com/",
    "screenWidth": 1920,
    "screenHeight": 1080,
    "devicePixelRatio": 1,
    "language": "en-US"
  },
  "webVitals": {
    "lcp": { "value": 1200, "element": "IMG" },
    "cls": { "value": 0.05 },
    "inp": { "value": 85 },
    "ttfb": { "value": 320 }
  },
  "longTasks": [],
  "performanceTimings": [],
  "memorySnapshots": [],
  "rageClicks": [],
  "stats": {
    "totalEvents": 5,
    "networkCalls": 1,
    "clicks": 1,
    "errors": 1,
    "consoleLogs": 1,
    "navigations": 1
  }
}
```

### Bug Diagnosis Response Format

```json
{
  "rootCause": {
    "summary": "POST /api/orders returned 500 due to database connection failure",
    "confidence": "90%",
    "triggerChain": [
      "User clicked Submit Order button",
      "POST /api/orders sent with valid payload",
      "Server returned 500: database connection failed",
      "JS error: Cannot read property 'orderId' of undefined (response was error, not order)"
    ],
    "evidence": [
      "Network call to /api/orders returned 500 at timestamp 1712900001000",
      "Response body: {\"error\":\"database connection failed\"}",
      "TypeError at checkout.js:42 — code expected orderId in response but got error object"
    ],
    "fix": "Add error handling in handleResponse() to check response.ok before accessing orderId"
  },
  "bugReport": {
    "title": "Order submission fails with 500 — unhandled error response causes TypeError",
    "description": "When submitting an order, the backend returns a 500 error...",
    "severity": "HIGH",
    "timeline": [
      "T+0s: User navigated to /checkout",
      "T+10s: User clicked Submit Order",
      "T+11.2s: POST /api/orders → 500 (1240ms)",
      "T+11.2s: TypeError: Cannot read property 'orderId'"
    ],
    "networkActivity": "1 failed request: POST /api/orders → 500",
    "consoleLogs": "1 error: 'Order submission failed'",
    "environment": "Chrome on Windows, 1920x1080"
  },
  "reproductionSteps": {
    "steps": [
      "1. Navigate to https://example.com/checkout",
      "2. Add an item to cart",
      "3. Click the Submit Order button"
    ],
    "curlCommands": [
      "curl -X POST https://api.example.com/orders -H 'Content-Type: application/json' -d '{\"items\":[{\"id\":123}]}'"
    ],
    "expectedBehavior": "Order is created and user is redirected to confirmation page",
    "actualBehavior": "Server returns 500 error, JS TypeError thrown, user sees broken page"
  }
}
```

### Configuration

`backend/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: session-replay
  ai:
    openai:
      api-key: YOUR_GEMINI_API_KEY        # Get free key at https://aistudio.google.com
      base-url: https://generativelanguage.googleapis.com/v1beta/openai/
      chat:
        options:
          model: gemini-2.5-flash          # Free tier, good for analysis
          temperature: 0.3
          max-tokens: 16384

logging:
  level:
    root: INFO
    org.springframework.ai: DEBUG          # See full AI request/response in logs
```

Spring AI's OpenAI-compatible starter (`spring-ai-starter-model-openai`) works with Gemini because Google provides an OpenAI-compatible endpoint. The only config needed is `base-url` and `api-key`.

## Browser Extension

### Manifest V3 Configuration

- **Permissions**: `activeTab`, `storage`, `downloads`, `webRequest`
- **Host Permissions**: `<all_urls>`
- **Content Scripts**:
  - `page-interceptor.js` — runs in **MAIN world** at `document_start` (intercepts page's actual fetch/XHR)
  - `content-script.js` — runs in **ISOLATED world** at `document_start` (captures user interactions, stores data)
- **Background**: Service worker with `chrome.webRequest` listeners

### Why Two Content Scripts (MAIN vs ISOLATED world)

Chrome extensions run content scripts in an **isolated JavaScript context** by default. This means monkey-patching `window.fetch` in the isolated world does NOT intercept the page's network calls — the page has its own `window.fetch`.

To intercept the page's actual network calls, `page-interceptor.js` runs in the **MAIN world** (the page's own JavaScript context) at `document_start`, before any page scripts execute. It patches `fetch` and `XMLHttpRequest` on the real `window` object.

Communication between the two worlds uses `window.postMessage`:

```
MAIN world (page-interceptor.js)          ISOLATED world (content-script.js)
─────────────────────────────────          ──────────────────────────────────
Intercepts fetch/XHR/console/errors  ───▶  Receives via window.addEventListener('message')
Posts TRACECRAFT_* messages                Stores in chrome.storage.local
                                           Captures clicks/keyboard/scroll/forms
Listens for TRACECRAFT_CONTROL       ◀───  Sends start/stop commands
```

### What Gets Captured

| Category | Data Points | Captured By |
|----------|-------------|-------------|
| **Network (fetch)** | URL, method, query params, request headers (auth redacted), request body, response status, response headers, response body (100KB cap), duration, initiator type | page-interceptor.js (MAIN) |
| **Network (XHR)** | Same as fetch, plus `setRequestHeader` tracking | page-interceptor.js (MAIN) |
| **Console** | All levels (log, info, warn, error, debug), serialized args | page-interceptor.js (MAIN) |
| **JS Errors** | Uncaught errors with full stack trace, filename, line/col | page-interceptor.js (MAIN) |
| **Promise Rejections** | Unhandled rejection reason and stack | page-interceptor.js (MAIN) |
| **SPA Navigation** | pushState, replaceState, popstate with URLs | page-interceptor.js (MAIN) |
| **Memory** | usedJSHeapSize, totalJSHeapSize every 10s | page-interceptor.js (MAIN) |
| **Clicks** | x/y, target element (tag, id, classes, CSS selector, rect, aria-label, role), button, modifiers | content-script.js (ISOLATED) |
| **Keyboard** | Key, code, target element; password/credit card fields redacted | content-script.js (ISOLATED) |
| **Scroll** | scrollX/Y, page height, viewport height; throttled to 250ms | content-script.js (ISOLATED) |
| **Form Submissions** | Action URL, method, field names/values (sensitive fields redacted) | content-script.js (ISOLATED) |
| **Visibility** | Page visibility state changes (visible/hidden) | content-script.js (ISOLATED) |
| **CSP Violations** | Blocked URI, violated directive | content-script.js (ISOLATED) |
| **Web Vitals** | LCP (value + element), CLS, INP (p98), TTFB, DOM interactive/complete | content-script.js (ISOLATED) |
| **Long Tasks** | Start time, duration, blocking time (>50ms main thread blocks) | content-script.js (ISOLATED) |
| **Resource Timings** | DNS, TCP, TLS, TTFB, download time, transfer size, protocol (for fetch/XHR resources) | content-script.js (ISOLATED) |
| **Resource Metadata** | Resource type, from-cache, server IP, content-type (for ALL resources including images/scripts) | background.js (webRequest) |

### Privacy

- Authorization, Cookie, and API key headers are partially redacted (first 10 chars + `[REDACTED]`)
- Password and credit card input fields have their keystrokes replaced with `[REDACTED]`
- Form fields matching sensitive patterns (password, token, CVV, SSN) are redacted
- Response bodies are capped at 100KB; binary responses show only size and MIME type

### Extension Popup Buttons

| Button | What it does |
|--------|-------------|
| **Start Recording** | Tells content script to begin capture; starts live stat polling every 1.5s |
| **Stop Recording** | Stops capture; reads final data from storage |
| **Export JSON** | Downloads the raw recording as a `.json` file (no backend needed) |
| **Full Analysis** | Sends to `POST /api/analyze` — broad session health: score, issues, network report, UX report, recommendations |
| **Network Bottlenecks** | Sends to `POST /api/network-bottlenecks` — focused: slow endpoints, N+1 patterns, redundant calls, CORS, compression |
| **Bug Diagnosis** | Sends to `POST /api/bug-diagnosis` — root cause with trigger chain, bug report, reproduction steps with curl commands |
| **Clear** | Wipes chrome.storage.local and resets UI |

### Popup Stats Display

The popup shows live counters during recording:
- Network, Clicks, Errors, Console, Navigation, Keyboard, Scroll, Long Tasks

Plus a Web Vitals section (LCP, CLS, INP, TTFB) with color-coded ratings:
- Green = Good, Orange = Needs Improvement, Red = Poor

## Development Commands

### Backend

```bash
# Build the project
cd backend
./mvnw package -DskipTests

# Run the backend
./mvnw spring-boot:run

# Verify it's running
curl http://localhost:8080/api/health
# Expected: {"status":"UP"}
```

Requires Java 17+ on the PATH. The project currently runs on Java 22.

### Browser Extension

1. Go to `chrome://extensions/`
2. Enable "Developer mode" (top right toggle)
3. Click "Load unpacked" and select the `browser-extension/` folder
4. Click the extension icon in the toolbar to open the popup
5. After making changes, click the refresh icon on the extension card in `chrome://extensions/`

## Testing

### Test 1: Backend Health Check

```bash
cd backend && ./mvnw spring-boot:run

# In another terminal:
curl http://localhost:8080/api/health
# {"status":"UP"}
```

### Test 2: End-to-End Recording + Analysis

1. Start the backend: `cd backend && ./mvnw spring-boot:run`
2. Load/refresh the extension in `chrome://extensions/`
3. Navigate to a test website (see recommendations below)
4. Click the extension icon → **Start Recording**
5. Interact with the page (click links, submit forms, trigger errors)
6. Click **Stop Recording**
7. Click any analysis button — result downloads as a `.json` file
8. Wait ~30 seconds between analysis buttons to avoid Gemini rate limits

### Test 3: Bug Diagnosis Workflow

1. Start the backend
2. Navigate to `https://the-internet.herokuapp.com/javascript_error` (JS error fires on load)
3. Start Recording
4. Navigate to `https://the-internet.herokuapp.com/login` — submit wrong credentials
5. Navigate to `https://the-internet.herokuapp.com/broken_images` — click broken images
6. Stop Recording
7. Click **Bug Diagnosis**
8. The downloaded JSON contains root cause, bug report, and reproduction steps

### Recommended Test Websites

| Website | What it tests |
|---------|---------------|
| **https://the-internet.herokuapp.com** | Broken images, JS errors, slow loading, failed logins, redirects |
| **https://reqres.in** | Live API calls with visible request/response — good for network capture testing |
| **https://automationexercise.com** | Full e-commerce: search, cart, forms — generates lots of network + interaction data |
| **https://demoqa.com** | Forms, buttons, alerts, dynamic elements, broken links |

### Expected Outputs

| Endpoint | What you get |
|----------|-------------|
| `/api/health` | `{"status": "UP"}` |
| `/api/analyze` | Health score, categorized issues, network report, UX report, prioritized recommendations |
| `/api/network-bottlenecks` | Slow endpoints, N+1 patterns, redundant calls, parallelizable requests, CORS overhead, compression issues |
| `/api/bug-diagnosis` | Root cause with confidence % and trigger chain, bug report with timeline, reproduction steps with curl commands |

## Configuration

### Changing the AI Model

Edit `backend/src/main/resources/application.yml`. The backend uses Spring AI's OpenAI-compatible starter, so any model behind an OpenAI-compatible endpoint works:

```yaml
spring:
  ai:
    openai:
      api-key: YOUR_KEY
      base-url: https://generativelanguage.googleapis.com/v1beta/openai/   # Gemini
      chat:
        options:
          model: gemini-2.5-flash    # or gemini-2.5-pro, gemini-2.0-flash, etc.
```

To use a different provider (e.g., OpenAI directly), just change `base-url` and `api-key`:

```yaml
spring:
  ai:
    openai:
      api-key: sk-your-openai-key
      base-url: https://api.openai.com/v1
      chat:
        options:
          model: gpt-4o
```

### Getting a Free Gemini API Key

1. Go to https://aistudio.google.com
2. Sign in with a Google account
3. Click "Get API key" → "Create API key"
4. Copy the key and paste it into `application.yml`

### Gemini Free Tier Limits

- Rate limited (wait ~30 seconds between requests)
- `gemini-2.5-flash` has the most generous free tier
- `gemini-2.5-pro` may have `limit: 0` on free tier — use Flash instead
- Recording payloads are auto-truncated to 80K chars to stay within token limits

## Debugging

### Content Script (ISOLATED world)
1. Right-click on webpage → Inspect → Console
2. Messages from the content script appear here

### Page Interceptor (MAIN world)
1. Same browser console as above
2. Network interception messages appear as regular console output from the page context

### Extension Popup
1. Right-click the extension icon → "Inspect Popup"
2. Check Console for JavaScript errors
3. Check Network tab to see API calls to localhost:8080

### Backend
1. Console output shows Spring AI request/response at DEBUG level
2. Look for `AI error on /endpoint` log lines for failures
3. Common errors:
   - **429**: Gemini rate limit — wait 30 seconds
   - **JsonMappingException**: Gemini returned unexpected JSON structure (mitigated by using `Object` types)
   - **Template string not valid**: Fixed — no longer uses Spring AI template params for user data

## Known Limitations

1. **Gemini free tier rate limits**: Wait ~30 seconds between analysis requests. The popup shows a readable error message when rate limited.

2. **Response body capture is limited to same-origin or CORS-allowed responses**: For cross-origin requests, `response.clone().text()` may fail if CORS doesn't expose the body. Binary responses show only size and MIME type.

3. **Performance timing detail depends on `Timing-Allow-Origin`**: Cross-origin resources report zero for DNS/TCP/TLS/TTFB timing unless the server sends `Timing-Allow-Origin: *`.

4. **Recording state survives page reloads** (via `chrome.storage.local`), but the MAIN world interceptor reinitializes on each page load. The content script detects this and re-sends the start command.

5. **Large recordings may be truncated**: Payloads over 80K characters are truncated before being sent to Gemini. For very long sessions, network response bodies dominate the payload size.

6. **`@CrossOrigin(origins = "*")`**: The backend accepts requests from any origin. Acceptable for local development but should be restricted in production.

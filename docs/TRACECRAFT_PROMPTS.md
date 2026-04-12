# TraceCraft Genesis — Build Prompts

Use these prompts sequentially with any AI coding assistant to recreate the full project. Each prompt produces one or more files. Wait for each to complete before moving to the next.

Prerequisites: Java 17+ installed, Chrome browser.

---

## Prompt 1: Project scaffold + Backend pom.xml

```
Create a Spring Boot 3.3.5 Maven project for a session replay analysis tool.

Project structure:
- Group: com.hackathon
- Artifact: session-replay
- Version: 2.0.0
- Java version: 17

Create backend/pom.xml with these dependencies:
- spring-boot-starter-parent 3.3.5 as parent
- spring-ai-bom 1.0.0 in dependencyManagement (type: pom, scope: import)
- spring-boot-starter-web
- spring-ai-starter-model-openai (version managed by BOM)
- jackson-databind
- spring-boot-starter-test (test scope)

Include the spring-boot-maven-plugin in build plugins.

Also create backend/src/main/java/com/hackathon/sessionreplay/SessionReplayApplication.java — standard @SpringBootApplication main class.

Also create backend/src/main/resources/application.yml:
- server.port: 8080
- spring.application.name: session-replay
- spring.ai.openai.api-key: YOUR_GEMINI_API_KEY
- spring.ai.openai.base-url: https://generativelanguage.googleapis.com/v1beta/openai/
- spring.ai.openai.chat.options.model: gemini-2.5-flash
- spring.ai.openai.chat.options.temperature: 0.3
- spring.ai.openai.chat.options.max-tokens: 65536
- logging.level.root: INFO
- logging.level.org.springframework.ai: DEBUG

Note: We use Spring AI's OpenAI-compatible starter pointed at Google's Gemini OpenAI-compatible endpoint. Get a free API key at https://aistudio.google.com
```

---

## Prompt 2: Backend model records

```
Create backend/src/main/java/com/hackathon/sessionreplay/model/AnalysisModels.java

This file defines Java records for parsing AI (Gemini) JSON responses. CRITICAL: Use Object type for ALL non-String fields because Gemini returns inconsistent types (numbers as strings, arrays as counts, booleans as strings). Add @JsonIgnoreProperties(ignoreUnknown = true) on every record.

Records needed:

1. SessionAnalysis: summary (String), healthScore (Object), issues (Object), networkReport (Object), uxReport (Object), recommendations (Object)

2. NetworkBottleneckReport: summary (String), networkHealthScore (Object), totalRequests (Object), failedRequests (Object), totalDurationMs (Object), slowEndpoints (Object), nplusOnePatterns (Object), parallelizableGroups (Object), redundantCalls (Object), largePayloads (Object), corsPreflights (Object), compressionIssues (Object), recommendations (Object)

3. BugDiagnosis: rootCause (Object), bugReport (Object), reproductionSteps (Object)

Import com.fasterxml.jackson.annotation.JsonIgnoreProperties.
```

---

## Prompt 3: Backend AIService

```
Create backend/src/main/java/com/hackathon/sessionreplay/service/AIService.java

This is a @Service that uses Spring AI's ChatClient to send session recording data to Gemini for analysis. 

CRITICAL RULES (learned from debugging):
1. Do NOT use Spring AI template parameters (.param("data", data)) — the recording JSON contains { and } which breaks the template engine. Use plain string concatenation instead.
2. Do NOT use .entity() for response parsing — Gemini wraps JSON in markdown code fences. Use .content() and parse manually.
3. Configure ObjectMapper with DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false.

Constructor:
- Inject ChatClient.Builder, build with SimpleLoggerAdvisor
- Create ObjectMapper with FAIL_ON_UNKNOWN_PROPERTIES = false

Methods:

1. analyzeSession(Map<String, Object> recording) -> SessionAnalysis
   System prompt: expert browser session analyst, must respond with ONLY raw JSON, no markdown code fences
   User prompt: schema description for SessionAnalysis fields + "\nSESSION DATA:\n" + data
   
2. analyzeNetworkBottlenecks(Map<String, Object> recording) -> NetworkBottleneckReport
   System prompt: network performance expert, must respond with ONLY raw JSON
   User prompt: schema for slow endpoints, N+1 patterns, parallelizable groups, redundant calls, large payloads, CORS preflights, compression issues + data

3. diagnoseBug(Map<String, Object> recording) -> BugDiagnosis
   System prompt: senior software engineer debugging a bug, must respond with ONLY raw JSON
   User prompt: schema for rootCause (summary, confidence, triggerChain, evidence, fix), bugReport (title, description, severity, timeline, networkActivity, consoleLogs, environment), reproductionSteps (steps, curlCommands, expectedBehavior, actualBehavior) + data

Helper methods:

parseResponse(String raw, Class<T> type):
- Strip markdown code fences (```json ... ```) if present
- Try parsing with objectMapper.readValue
- If that fails, call repairTruncatedJson() and retry
- If still fails, throw RuntimeException with message

repairTruncatedJson(String json):
- Walk the string tracking inString, escaped, braceDepth, bracketDepth
- If truncated inside a string, close the string
- Remove trailing commas
- Recount open braces/brackets and append closing characters
- This handles Gemini responses that get cut off mid-JSON

formatRecording(Map<String, Object> recording):
- Serialize to pretty-printed JSON with objectMapper
- Truncate to 80000 chars if longer (to stay within Gemini free tier token limits)
- Append "...[truncated, N total chars]" message
```

---

## Prompt 4: Backend RecordingController

```
Create backend/src/main/java/com/hackathon/sessionreplay/api/RecordingController.java

@RestController @RequestMapping("/api") @CrossOrigin(origins = "*")

Endpoints:
- POST /api/analyze -> calls aiService.analyzeSession(), returns SessionAnalysis
- POST /api/network-bottlenecks -> calls aiService.analyzeNetworkBottlenecks(), returns NetworkBottleneckReport
- POST /api/bug-diagnosis -> calls aiService.diagnoseBug(), returns BugDiagnosis
- GET /api/health -> returns {"status": "UP"}

All POST endpoints accept @RequestBody Map<String, Object>.

Error handling: wrap each AI call in try/catch. The handleAiError method checks if the error message contains "429" and returns HTTP 429 with "Rate limit exceeded. Please wait 30 seconds and try again." Otherwise returns 500 with the first 200 chars of the error message.

Use slf4j Logger for error logging.
```

---

## Prompt 5: Chrome Extension manifest.json

```
Create browser-extension/manifest.json for a Chrome Manifest V3 extension.

Name: "TraceCraft Genesis — Session Replay"
Version: 2.0.0
Description: "Record complete browser sessions — clicks, network calls with payloads, console logs, errors, performance metrics — and analyze with AI"

Permissions: activeTab, storage, downloads, webRequest
Host permissions: <all_urls>

Action: default_popup points to popup/popup.html
Background: service_worker src/background.js

Content scripts — TWO entries, both matching <all_urls>, both run_at document_start:
1. src/page-interceptor.js with world: "MAIN"
2. src/content-script.js with world: "ISOLATED"

Web accessible resources: src/page-interceptor.js for <all_urls>

IMPORTANT: The MAIN world script is critical — it runs in the page's JavaScript context so it can intercept the page's actual fetch/XHR calls. An ISOLATED world script cannot do this because it has a separate JavaScript context.
```

---

## Prompt 6: page-interceptor.js (MAIN world)

```
Create browser-extension/src/page-interceptor.js

This is the most important file. It runs in the page's MAIN world at document_start, BEFORE any page scripts execute. It intercepts the page's actual network calls, console, errors, and navigation.

Wrap everything in an IIFE with 'use strict'.

State: let intercepting = false (controlled via postMessage from the isolated world content script)

Communication: post data via window.postMessage with source: 'TRACECRAFT_INTERCEPTOR'. Listen for control messages with source: 'TRACECRAFT_CONTROL' (action: 'start' or 'stop').

Intercept these:

1. FETCH — wrap window.fetch:
   - If not intercepting, pass through to original
   - Extract: url, method, request headers (from Headers/object), request body (clone Request if needed), query params (via URL constructor)
   - Call original fetch, clone the response
   - Read response headers, body (async, from clone — text for JSON/text/JS/XML, blob size for binary, cap at 100KB)
   - Post NETWORK message with: id, timestamp, type:'fetch', method, url, queryParams, requestHeaders (redacted), requestBody, status, statusText, responseHeaders, responseBody, responseSize, duration, pageUrl, initiatorType:'fetch'
   - On error: post with status:0, error message
   - IMPORTANT: always return the original response, not the clone

2. XMLHttpRequest — patch prototype.open, prototype.setRequestHeader, prototype.send:
   - open: store method, url, init reqHeaders object
   - setRequestHeader: accumulate headers
   - send: if not intercepting, pass through. Otherwise store body, start time
   - Add load/error/abort event listeners with { once: true } (IMPORTANT: prevents listener stacking on reused XHR instances)
   - On load: parse response headers from getAllResponseHeaders(), read responseBody based on responseType (text, json via JSON.stringify, arraybuffer/blob show size), post NETWORK message
   
3. CONSOLE — wrap log, info, warn, error, debug:
   - Store original, replace with function that posts CONSOLE message then calls original
   - Serialize args: null->'null', undefined->'undefined', objects via JSON.stringify, else String()
   
4. ERRORS — window.addEventListener('error') and ('unhandledrejection'):
   - Post ERROR message with type, message, filename, lineno, colno, stack, pageUrl

5. SPA NAVIGATION — wrap history.pushState, history.replaceState, listen for popstate:
   - Post NAVIGATION message with type, url, pageUrl

6. MEMORY — setInterval every 10s, if intercepting and performance.memory exists, post MEMORY message

Helper functions:
- extractBody(body): handle string, URLSearchParams, FormData (with File detection), Blob, ArrayBuffer. Cap strings at 100KB.
- readResponseBody(response): async, check content-type, text for text types, blob size for binary. Cap at 100KB.
- redactHeaders(headers): partially redact authorization, cookie, set-cookie, x-api-key, x-auth-token (show first 10 chars + [REDACTED])
- parseQueryParams(url): parse with URL constructor, return object
- generateId(): 'tc_' + Date.now() + '_' + counter
- post(type, payload): window.postMessage wrapped in try/catch

On load: post INTERCEPTOR_READY message so the content script knows to send the start command if recording was in progress.
```

---

## Prompt 7: content-script.js (ISOLATED world)

```
Create browser-extension/src/content-script.js

Runs in Chrome's ISOLATED world. Receives intercepted data from the MAIN world via window.postMessage. Captures user interactions directly. Stores everything in chrome.storage.local. Communicates with popup via chrome.runtime.onMessage.

Wrap in IIFE with 'use strict'.

Session data structure:
clicks, networkCalls, consoleLogs, errors, navigations, scrollEvents, keyboardEvents, formSubmissions, visibilityChanges, webVitals (object), longTasks, memorySnapshots, performanceTimings, metadata (startTime, stopTime, startUrl, userAgent, screen dimensions, devicePixelRatio, language)

Storage: batched saves with 1-second debounce (scheduleSave function with setTimeout)

On load: check chrome.storage.local for isRecording — if true, restore sessionData and send TRACECRAFT_CONTROL start message to MAIN world. Setup performance observers.

Listen for window messages from TRACECRAFT_INTERCEPTOR:
- NETWORK -> push to networkCalls
- CONSOLE -> push to consoleLogs  
- ERROR -> push to errors
- NAVIGATION -> push to navigations
- MEMORY -> push to memorySnapshots
- INTERCEPTOR_READY -> if recording, re-send start command (handles page reload)

Capture directly:
1. Clicks (capture phase): timestamp, x/y, pageX/Y, target details (tag, id, classes, name, type, href, text, ariaLabel, role, computed CSS selector, bounding rect), pageUrl, button, modifiers
2. Keyboard (capture phase): key (REDACTED for password/credit card fields), code, target info, sensitive detection
3. Scroll (passive, throttled 250ms): scrollX/Y, page height, viewport height
4. Form submission (capture phase): action, method, fields (sensitive patterns redacted)
5. Visibility changes: document.visibilityState
6. CSP violations: securitypolicyviolation event -> push to errors

Performance observers (in setupPerformanceObservers function, called on start):
- LCP (largest-contentful-paint, buffered)
- CLS (layout-shift, buffered, accumulate, skip hadRecentInput)
- Long tasks (longtask, buffered, record duration and blocking time)
- Resource timings (resource, buffered, only fetch/xmlhttprequest — DNS, TCP, TLS, TTFB, download, transfer size, protocol)
- Navigation timing (getEntriesByType('navigation') — TTFB, domInteractive, domComplete)
- INP via event timing (buffered, durationThreshold:16, track P98 of interaction durations)

CSS selector utility: getCSSSelector(el) — walk up the DOM, use #id when found, otherwise tag:nth-of-type(n)

Message handling (chrome.runtime.onMessage):
- startRecording: reset sessionData, set isRecording=true, postMessage start to MAIN world, save to storage, setup observers, sendResponse({success:true})
- stopRecording: set stopTime, isRecording=false, postMessage stop, save, sendResponse({success:true, data:sessionData})
- getState: sendResponse with isRecording, stats counts, webVitals
- return true to keep message channel open
```

---

## Prompt 8: background.js

```
Create browser-extension/src/background.js

Service worker that captures network metadata via chrome.webRequest API (resource type, from-cache, server IP — data not available to content scripts).

Use chrome.webRequest listeners:
1. onBeforeRequest (urls: <all_urls>, extraInfoSpec: ['requestBody']): store requestId, url, method, type, tabId, timestamp. Capture requestBody (formData or raw bytes decoded to text).
2. onHeadersReceived (extraInfoSpec: ['responseHeaders']): store statusCode and response headers.
3. onCompleted: store fromCache, ip, duration. Save to chrome.storage.session per tab (key: 'resourceMetadata_' + tabId). Keep last 500 entries per tab.
4. onErrorOccurred: clean up the request entry.

Clean up stale entries every 60 seconds.

On runtime.onInstalled: log version message.
```

---

## Prompt 9: Popup HTML + CSS

```
Create browser-extension/popup/popup.html and popup.css

HTML structure:
- h1: "TraceCraft Genesis"
- Status div (id: status)
- Stats grid (4 columns): Network, Clicks, Errors, Console, Navigation, Keyboard, Scroll, Long Tasks — each with label and value span
- Web Vitals section (hidden initially): LCP, CLS, INP, TTFB — each with label and value, values get color-coded classes (good/needs-improvement/poor)
- Recording controls: Start Recording (green), Stop Recording (red, disabled)
- Action buttons (hidden until data): Export JSON, Full Analysis (blue), Network Bottlenecks (blue), Bug Diagnosis (orange), Clear
- API status message area
- Recording summary preview (hidden, with pre element)

CSS: Dark theme
- Body: #0f1117, width 360px
- Container: #1a1d27, rounded corners, border #2a2d3a
- Stats: 4-column grid, #22252f backgrounds
- Buttons: green (.primary), red (.danger), blue (.accent), orange (.bug), dark gray (.secondary)
- Recording status: pulsing animation
- Web vital colors: green (.good), orange (.needs-improvement), red (.poor)
- Monospace font for preview
```

---

## Prompt 10: Popup JavaScript

```
Create browser-extension/popup/popup.js

Main popup logic. API_URL = 'http://localhost:8080/api'

Init: read isRecording and sessionData from chrome.storage.local. Update UI. Start polling if recording.

UI State:
- Recording: show "Recording...", disable start, enable stop, hide action buttons
- Has data: show "Recording Available", show action buttons
- Ready: default state

Polling: setInterval 1.5s reading sessionData from storage, updating stats

Recording controls:
- Start: send startRecording to content script via chrome.tabs.sendMessage. On success: set state, reset stats, start polling
- Stop: stop polling, send stopRecording. Fallback to reading storage directly if tab message fails. Show stop summary.

Analysis buttons:
- Full Analysis -> POST /api/analyze
- Network Bottlenecks -> POST /api/network-bottlenecks
- Bug Diagnosis -> POST /api/bug-diagnosis

sendToApi(endpoint, label) function:
- Build payload with buildPayload()
- Disable buttons, show "Sending..." status
- fetch POST with JSON body
- IMPORTANT: wrap response.json() in try/catch — backend may return HTML errors instead of JSON
- Check response.ok, show error from result.error if not ok
- On success: download result as JSON file via chrome.downloads, revoke blob URL in the download callback
- Use finally block to re-enable buttons (not duplicated in try and catch)

buildPayload(): 
- Transform sessionData into unified event timeline (clicks, network, console, errors, navigations, form_submissions), sorted by timestamp
- Include metadata, webVitals, longTasks, performanceTimings, memorySnapshots, scrollEvents, keyboardEvents
- Pre-compute rage clicks and include them
- Include stats counts

detectRageClicks(clicks): 3+ clicks within 1000ms, within 40px distance. Skip past clusters. Return array with element selector, click count, window duration, page URL.

Export: download raw sessionData as JSON file
Clear: wipe chrome.storage.local, reset all UI

Web vital thresholds for coloring: LCP <=2500 good, <=4000 needs-improvement. CLS <=0.1 good, <=0.25. INP <=200 good, <=500. TTFB <=800 good, <=1800.
```

---

## Prompt 11: Maven wrapper setup

```
Set up the Maven wrapper for the backend:

1. Create backend/.mvn/wrapper/maven-wrapper.properties:
   distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip
   wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar

2. Download the wrapper jar:
   curl -sL -o backend/.mvn/wrapper/maven-wrapper.jar "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

3. Create backend/mvnw (shell script):
   IMPORTANT: Use -cp not -jar — the Maven wrapper jar has no Main-Class manifest attribute.
   java -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"

4. Create backend/mvnw.cmd (Windows):
   java -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*

5. chmod +x backend/mvnw

6. Test: cd backend && ./mvnw package -DskipTests
```

---

## Prompt 12: Build and test

```
Build and test the complete project:

1. cd backend && ./mvnw package -DskipTests
   - Should output BUILD SUCCESS

2. ./mvnw spring-boot:run
   - Should start on port 8080

3. curl http://localhost:8080/api/health
   - Should return {"status":"UP"}

4. Load the extension:
   - Go to chrome://extensions/
   - Enable Developer mode
   - Click "Load unpacked" -> select browser-extension/ folder

5. Test recording:
   - Navigate to https://the-internet.herokuapp.com/javascript_error
   - Click extension icon -> Start Recording
   - Navigate to /login, submit wrong credentials
   - Navigate to /broken_images
   - Stop Recording
   - Click Export JSON to verify data capture
   - Click Bug Diagnosis (wait ~30 seconds for Gemini response)
   - Analysis JSON should download automatically

If you get rate limit errors (429), wait 30 seconds between analysis button clicks — this is a Gemini free tier limitation.
```

---

## Known Gotchas (share with the AI if it makes mistakes)

1. Spring AI artifact name is `spring-ai-starter-model-openai` (not the old `spring-ai-openai-spring-boot-starter`)
2. Do NOT use `.param("data", data)` in Spring AI prompts — JSON braces break the template engine. Concatenate directly.
3. Do NOT use `.entity(MyClass.class)` — Gemini wraps JSON in markdown fences. Use `.content()` + manual parse.
4. Use `Object` for ALL non-String record fields — Gemini returns numbers as strings, arrays as counts, etc.
5. Maven wrapper jar needs `-cp` not `-jar` (no Main-Class attribute). Also needs `-Dmaven.multiModuleProjectDirectory`.
6. Gemini 2.5 Pro may have `limit: 0` on free tier. Use `gemini-2.5-flash` instead.
7. Set max-tokens to 65536 — lower values truncate large JSON responses mid-output.
8. XHR event listeners need `{ once: true }` to prevent stacking on reused XHR instances.
9. Wrap `response.json()` in try/catch in popup.js — backend may return HTML errors.

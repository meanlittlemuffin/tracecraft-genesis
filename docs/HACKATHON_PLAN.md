# TraceCraft Genesis — 12-Hour Hackathon Build Plan

## Feasibility

Building the full project in 12 hours is tight but feasible if you cut low-demo-value features and focus on the core loop: **record network calls + errors → AI diagnoses the bug**.

## What Takes the Most Time

| Component | Estimated Effort | Why |
|-----------|-----------------|-----|
| MAIN world network interceptor (full req/res capture) | 2-3 hours | The most complex piece — cloning responses, handling FormData/Blob, XHR patching, postMessage bridge |
| Spring Boot 3.3 + Spring AI setup | 1-1.5 hours | Maven dependency issues, Gemini config, figuring out template quirks |
| AI prompt engineering + JSON parsing | 1.5-2 hours | Getting Gemini to return valid JSON consistently, handling inconsistent types, stripping markdown fences |
| Popup UI with stats + multiple buttons | 1-1.5 hours | HTML/CSS/JS, live polling, payload building, rage click pre-computation |
| Content script (clicks, keyboard, scroll, Web Vitals) | 1-1.5 hours | Many small observers, privacy redaction, batched storage |
| Background.js webRequest | 30 min | Straightforward API |
| Testing + debugging | 1-2 hours | Things break at integration points |

**Total if building everything: 9-12 hours** — right at the edge.

## What to Cut

### Cut these — high effort, low demo value

| Feature | Why cut it |
|---------|-----------|
| Keyboard capture with privacy redaction | Nice to have, adds complexity, not visually impressive |
| Scroll event capture | Generates noise in the data, AI doesn't use it meaningfully |
| Memory snapshots | Requires MAIN world + interval, rarely shows anything interesting in a 5-min demo |
| Web Vitals (LCP, CLS, INP, TTFB) | PerformanceObserver setup is fiddly, and the values only matter for real production sites |
| Long task monitoring | Impressive on paper but won't show meaningful data on test sites |
| Resource performance timings (DNS/TCP/TLS breakdown) | Cross-origin zeros make this unreliable for demos |
| Background.js webRequest metadata | from-cache and server IP are supplementary data the AI doesn't really need |
| Form submission capture | Edge case — most demo flows won't involve forms meaningfully |
| CSP violation capture | Rarely fires on test sites |
| Visibility change capture | Low value |

### Keep these — they ARE the demo

| Feature | Why keep it |
|---------|-------------|
| MAIN world fetch/XHR interception with full req/res | This is the core value prop. Without it, you're recording nothing useful. |
| Console + error capture (MAIN world) | Errors are what make bug diagnosis work |
| SPA navigation capture | One line of code, makes the timeline make sense |
| Click capture with CSS selectors | Minimal effort, essential for UX context |
| Spring AI + Gemini integration | The AI is the whole point |
| Bug Diagnosis endpoint | The most impressive demo moment — "click one button, get root cause + bug report + curl commands" |
| Network Bottlenecks endpoint | Shows the tool finds real performance issues |
| Full Analysis endpoint | Broad overview, good for the "health check" story |
| Export JSON | Fallback if AI is rate-limited during demo |

## Hour-by-Hour Plan

| Hour | Task |
|------|------|
| 0-1 | Scaffold Spring Boot 3.3 project with Spring AI + Gemini. Get `/api/health` working. |
| 1-3 | Build `page-interceptor.js` (MAIN world) — fetch + XHR interception with full payloads, console + error capture, SPA navigation. This is the hardest part — do it while you're fresh. |
| 3-4 | Build `content-script.js` (ISOLATED world) — receive postMessage data, click capture, chrome.storage, message handling for start/stop. |
| 4-5 | Build `manifest.json` with both world scripts. Build simple `popup.html/css/js` — Start, Stop, Export, 3 analysis buttons. Wire up chrome.storage polling. |
| 5-6 | Lunch + test that recording works end-to-end. Open a website, start recording, click around, export JSON, verify network calls have full payloads. |
| 6-8 | Build `AIService.java` — the 3 analysis methods (analyzeSession, analyzeNetworkBottlenecks, diagnoseBug). Build `RecordingController.java`. Build `AnalysisModels.java`. This is where you'll fight with Gemini's JSON output — budget extra time. |
| 8-9 | Build `popup.js` payload builder — unified timeline, rage click detection, send to API, download result. |
| 9-10 | Integration testing. Record on test sites, run all 3 analysis buttons. Fix JSON parsing issues. |
| 10-11 | Polish. Fix bugs found in testing. Make the popup look good. |
| 11-12 | Prepare demo. Record a compelling bug scenario on `the-internet.herokuapp.com`. Have the analysis JSON ready as a backup in case Gemini rate-limits during the live demo. |

## Gotchas We Hit (Avoid These)

These are actual issues encountered during development that cost debugging time:

### 1. Spring AI template params break on JSON data
**Problem**: Using `.user(u -> u.text("...{data}...").param("data", data))` causes "template string is not valid" because Spring AI's template engine treats every `{` and `}` in the JSON recording as variable placeholders.
**Fix**: Use plain string concatenation: `.user("...SESSION DATA:\n" + data)`

### 2. Gemini wraps JSON in markdown code fences
**Problem**: You ask for "ONLY raw JSON" but Gemini returns `` ```json\n{...}\n``` ``. Spring AI's `.entity()` can't parse this.
**Fix**: Don't use `.entity()`. Use `.content()` and strip fences manually before parsing:
```java
String json = raw.strip();
if (json.startsWith("```")) {
    json = json.substring(json.indexOf('\n') + 1);
    if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
    json = json.strip();
}
return objectMapper.readValue(json, type);
```

### 3. Gemini returns inconsistent types
**Problem**: You define a record with `int severityScore` but Gemini returns `"Medium"` instead of a number. Or `List<RageClick> rageClicks` but Gemini returns `0` instead of `[]`.
**Fix**: Use `Object` for ALL non-String fields in your Java records from the start. Add `@JsonIgnoreProperties(ignoreUnknown = true)` on every record.

### 4. Gemini 2.5 Pro has zero free quota
**Problem**: `gemini-2.5-pro` returns `limit: 0` for free tier requests. The error is HTTP 429.
**Fix**: Use `gemini-2.5-flash` — it has a generous free tier and is still very capable for analysis.

### 5. max-tokens too low truncates JSON responses
**Problem**: `max-tokens: 4096` cuts off large structured JSON mid-response, causing Jackson parse errors ("unexpected end of input").
**Fix**: Set `max-tokens: 65536` in application.yml. Gemini 2.5 Flash supports up to 65K output tokens.

### 6. Content script runs in isolated world — can't intercept page network calls
**Problem**: Monkey-patching `window.fetch` in the content script's isolated world does NOT intercept the page's actual fetch calls. The page has its own JavaScript context.
**Fix**: Use a separate MAIN world script (`"world": "MAIN"` in manifest.json) that runs at `document_start` before any page scripts.

### 7. Maven wrapper jar is not directly executable
**Problem**: The `maven-wrapper.jar` from Maven Central has no `Main-Class` manifest attribute. Running `java -jar maven-wrapper.jar` fails.
**Fix**: Use `-cp` instead of `-jar`: `java -cp maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain`

## Pro Tips for the Hackathon

- **Have a pre-recorded backup**: Export a good recording JSON and a good analysis result JSON before the demo. If Gemini rate-limits you live, show the pre-recorded output.
- **Use `gemini-2.5-flash`** from the start — don't waste time debugging `gemini-2.5-pro` quota issues.
- **Use `Object` types in all Java records from day one** — don't fight Gemini's type inconsistency.
- **Don't use Spring AI template params** — concatenate directly to avoid the template engine choking on JSON braces.
- **Skip `background.js` webRequest entirely** — it adds marginal data the AI doesn't need for an impressive demo.
- **The popup doesn't need to be pretty** for the first 8 hours. Functional > polished. Make it look good in the last 2 hours.
- **Truncate recording payloads to 80K chars** before sending to Gemini — large recordings with ad network blobs will blow through token limits.
- **Wait ~30 seconds between Gemini requests** on the free tier — the popup should show a readable error when rate-limited, not a 500 stack trace.
- **Test on `the-internet.herokuapp.com`** — it has pages with JS errors, broken images, slow loading, and failed logins. Perfect for demoing bug diagnosis.

## Test Websites

| Website | What it tests |
|---------|---------------|
| **https://the-internet.herokuapp.com** | JS errors (`/javascript_error`), broken images, slow loading, failed logins, redirects |
| **https://reqres.in** | Live API calls with visible request/response — good for verifying network capture |
| **https://automationexercise.com** | Full e-commerce: search, cart, forms — generates lots of network + interaction data |
| **https://demoqa.com** | Forms, buttons, alerts, dynamic elements |

## Demo Script (5 minutes)

1. **Show the extension popup** — explain the Start/Stop/Analyze workflow (30s)
2. **Start recording on `the-internet.herokuapp.com/javascript_error`** — show the JS error counter increment (30s)
3. **Navigate to `/login`, submit wrong creds** — show network + click counters growing (30s)
4. **Stop recording** — show the stats summary (15s)
5. **Click Export JSON** — briefly show the raw data with full network payloads (30s)
6. **Click Bug Diagnosis** — while waiting, explain what the AI is analyzing (30s)
7. **Open the downloaded JSON** — walk through the root cause, trigger chain, bug report, and curl commands (2 min)
8. **If time: click Network Bottlenecks** — show it identifies slow endpoints and redundant calls (30s)

**Backup**: If Gemini is rate-limited during the live demo, open the pre-exported analysis JSON and walk through it. The audience won't know the difference.

# Server-Log-Assisted RCA With Gemini Free-Tier Optimization

## Summary

Implement server-log-assisted RCA for localhost demo apps, but do not send raw full recordings or raw log files to Gemini. Instead, add a backend-side "incident packet" builder that compresses browser and server evidence into a small, ranked, structured payload before each AI call.

This keeps the extension unchanged, improves RCA quality, and reduces the chance of hitting Gemini free-tier rate or token limits.

## Key Changes

### Backend incident-packet builder
- Add a backend service that transforms the incoming browser recording plus correlated server logs into a compact AI-ready object.
- Use this compact object for `/api/bug-diagnosis` always, and for `/api/analyze` only when useful server-log matches exist.
- Do not enrich `/api/network-bottlenecks` with server logs in v1.

### Request-size strategy
- Replace raw pretty-printed full-recording submission with a curated `incidentPacket`.
- Hard-target prompt size for `bug-diagnosis`: 20K-35K characters.
- Hard ceiling for any AI request: 40K characters.
- If the packet exceeds the cap, trim lowest-priority sections first in this order:
  - successful network calls
  - scroll events
  - keyboard events
  - low-level console logs
  - extra timeline entries
  - excess server-log context lines
- Keep at least:
  - metadata summary
  - top failure timeline
  - failed/slow network calls
  - browser errors
  - top correlated server-log excerpts

### Incident-packet structure
Use a compact internal payload with these sections:

- `sessionSummary`
  - start URL
  - host
  - start/stop time
  - duration
  - user agent summary
  - event counts
- `highSignalTimeline`
  - 20-30 events max
  - only failed/slow network calls, uncaught errors, console error/warn, form submissions, critical navigations
- `networkEvidence`
  - 10-15 important requests max
  - prioritize 5xx, 4xx, timeouts, aborted requests, very slow requests
  - include method, path, status, duration, short request/response snippets only when relevant
- `browserEvidence`
  - top JS errors
  - top console errors/warnings
  - rage clicks if near a failure
- `serverLogContext`
  - enabled flag
  - source path
  - correlation window
  - matched routes
  - 50-100 matched log lines max
  - preserve stack traces adjacent to matched exception lines
  - summary counts of error/warn/exception matches
- `correlationHints`
  - matched routes between browser and server
  - nearest timestamps
  - likely failing request candidates
- `truncationInfo`
  - which sections were dropped or trimmed

### Browser-data compaction rules
- Do not send the entire `events` array as-is.
- Exclude most successful network calls unless needed for sequence context.
- Exclude raw response bodies for successful requests.
- Cap error/console message length.
- Include request and response body snippets only for failed requests and only up to a small per-field limit.
- Exclude `scrollEvents`, `keyboardEvents`, `memorySnapshots`, and `performanceTimings` from `bug-diagnosis` unless they directly support the failure.
- For `/api/analyze`, keep broader stats but still summarize instead of dumping raw arrays.

### Server-log compaction rules
- Read only the tail of the configured log file.
- Filter by padded time window plus route/keyword matching.
- Rank matches by:
  - exception/stack trace
  - `ERROR`
  - `WARN`
  - matching route/path
  - matching status code
  - proximity to failed browser request timestamps
- Keep 50-100 lines max for AI, including adjacent stack-trace lines where needed.
- If timestamps are unparseable, fall back to route and error-keyword matching only.

### Gemini usage control
- Add a lightweight backend cooldown for Gemini-backed endpoints:
  - if a request was made in the last ~20-30 seconds, return a friendly message or reuse cache if available
- Add result caching keyed by a stable hash of:
  - compact incident packet
  - endpoint name
- If the same recording is analyzed again with the same packet, return cached result instead of calling Gemini.
- Cache both success and recent rate-limit failures for a short TTL so repeated clicks do not hammer Gemini.

### AI prompt changes
- Update prompts to analyze a "curated incident packet" instead of "full session data".
- Explicitly tell Gemini:
  - the packet contains only high-signal evidence
  - browser and server logs refer to the same demo session
  - conclusions should prioritize evidence supported by both sources
  - if server logs are absent or inconclusive, say so rather than guessing
- Keep response schema stable except for the optional `serverLogSummary` in `BugDiagnosis`.

### Backend config
Extend `application.yml` with:
- `tracecraft.logs`
  - `enabled`
  - `demo-hosts`
  - `file-path`
  - `lookback-seconds`
  - `max-bytes-read`
  - `max-lines-for-ai`
- `tracecraft.ai`
  - `max-packet-chars: 40000`
  - `target-packet-chars: 30000`
  - `cooldown-seconds: 30`
  - `cache-enabled: true`
  - `cache-ttl-seconds: 900`

### Demo app in repo
- Add one in-repo localhost demo app on a different port than TraceCraft backend.
- Emit Spring-style text logs to a stable known file.
- Expose deterministic failure routes:
  - 500 error
  - slow endpoint
  - malformed JSON
  - validation failure
- Ensure logs include timestamp, level, route, status, and stack trace details for good correlation.

## Public Interfaces / Types

- Keep extension request payloads unchanged.
- Keep existing backend endpoints unchanged.
- Add optional `serverLogSummary` to `BugDiagnosis`.
- Add backend config under `tracecraft.logs` and `tracecraft.ai`.
- Do not add log-upload APIs, request-ID propagation, or multi-app support in v1.

## Test Plan

- Happy path:
  - record a localhost demo-app session that triggers a server exception
  - run `Bug Diagnosis`
  - verify the AI response cites both browser evidence and matching server logs
  - verify `serverLogSummary` explains what matched
- Free-tier optimization checks:
  - log final incident-packet character count before Gemini call
  - verify packet stays under 40K chars
  - verify repeat analysis of same session uses cache
  - verify rapid repeated clicks do not issue repeated Gemini calls during cooldown
- Correlation cases:
  - failed API request with matching exception
  - slow API request with matching slow log
  - frontend-only issue with no server-log evidence
  - multi-request session with one failing request
- Edge cases:
  - missing/unreadable log file
  - huge log file
  - no matching log lines
  - unparseable timestamps
  - non-localhost session should skip server-log enrichment
  - oversized packet should trim low-priority sections, not fail

## Assumptions and Defaults

- Scope is localhost demo only.
- One demo app, one log file, one backend process.
- Correlation uses time window plus route/method/status, not request IDs.
- Gemini free tier is treated as scarce: one compact high-signal request is preferred over multiple broad requests.
- Defaults:
  - target packet size: 30K chars
  - hard max packet size: 40K chars
  - max server-log lines for AI: 100
  - max high-signal timeline entries: 30
  - max important network calls: 15
  - cooldown: 30 seconds
  - cache TTL: 15 minutes

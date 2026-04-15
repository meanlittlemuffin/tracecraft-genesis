# TraceCraft Demo App

This is a local Spring Boot app used to generate controlled failures and slow responses for TraceCraft hackathon demos.

It helps you show stronger RCA by combining:
- browser recording data from the Chrome extension
- backend server logs from this app (`demo-app/logs/demo-app.log`)

## What It Simulates

- `500` server failure with stack trace
- slow API response
- malformed JSON response
- validation error (`400`)

These are intentionally deterministic so you can reproduce the same patterns during demos.

## Endpoints

- `GET /api/health`
- `GET /api/fail-500`
- `GET /api/slow`
- `GET /api/malformed-json`
- `POST /api/validate`

The UI page at `http://localhost:8090` calls these endpoints with one-click buttons.

## Run Locally

This folder does not include its own `mvnw.cmd`. Use the backend wrapper:

```bat
cd C:\Users\priya\tracecraft\tracecraft-genesis\demo-app
..\backend\mvnw.cmd -f pom.xml spring-boot:run
```

App starts on:
- `http://localhost:8090`

## Logging

Logs are written to:
- `C:\Users\priya\tracecraft\tracecraft-genesis\demo-app\logs\demo-app.log`

Log lines include timestamp, level, route, status, and request duration.

## Recommended Demo Flow

1. Start this demo app.
2. Start TraceCraft backend on `http://localhost:8080`.
3. Open `http://localhost:8090` and start recording in the extension.
4. Click at least two failure actions (for example `500` + `Malformed JSON`).
5. Stop recording and run Bug Diagnosis.
6. Confirm diagnosis includes browser evidence plus `serverLogSummary`.

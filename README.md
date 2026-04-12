# Session Replay Prototype

A hackathon prototype for recording user sessions and generating AI-powered bug reports.

## Architecture

```
┌─────────────────────────────────┐     ┌─────────────────────────────┐
│  Chrome Extension                │     │  Spring Boot Backend        │
│  • Start/Stop Recording        │────►│  (completions.me Claude Opus 4.6) │
│  • Capture clicks, network,    │     │                             │
│    console, errors             │     │  POST /api/analyze          │
│  • Export JSON file            │     │  POST /api/report          │
└─────────────────────────────────┘     │  POST /api/root-cause      │
                                        │  POST /api/reproduce       │
                                        └─────────────────────────────┘
```

## Components

### Browser Extension (`browser-extension/`)

- **Manifest V3** Chrome extension
- Captures: clicks, network calls (fetch/XHR), console logs, JavaScript errors
- Export recordings as JSON files

### Backend (`backend/`)

- **Spring Boot 3.4** application
- **Spring AI** with Anthropic Claude (Haiku)
- **MCP Server** with 4 tools:
  - `analyze_recording` - Structured summary
  - `generate_report` - Human-readable bug report
  - `root_cause_analysis` - Identify likely causes
  - `generate_reproduction_steps` - curl commands

## Setup

### 1. Backend

**Windows:**
```cmd
cd backend

# Set your completions.me API key in application.yml first!
# Get free key at https://completions.me

maven\bin\mvn.cmd spring-boot:run
```

**Linux/Mac:**
```bash
cd backend
# Set your completions.me API key in application.yml first!
maven/bin/mvn spring-boot:run
```

The backend starts on **http://localhost:8080**

### 2. Browser Extension

1. Open Chrome and go to `chrome://extensions/`
2. Enable "Developer mode" (toggle in top right)
3. Click "Load unpacked"
4. Select the `browser-extension/` folder
5. The extension icon appears in your toolbar

## Usage

### Recording a Session

1. Click the extension icon in Chrome toolbar
2. Click **"Start Recording"**
3. Navigate to the page with the issue
4. Reproduce the bug (click, make API calls, etc.)
5. Click **"Stop Recording"**
6. Click **"Export JSON"** to download the recording

### Analyzing with AI

```bash
# Analyze recording
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d @recording.json

# Generate report
curl -X POST http://localhost:8080/api/report \
  -H "Content-Type: application/json" \
  -d @recording.json

# Root cause analysis
curl -X POST http://localhost:8080/api/root-cause \
  -H "Content-Type: application/json" \
  -d @recording.json

# Generate reproduction steps
curl -X POST http://localhost:8080/api/reproduce \
  -H "Content-Type: application/json" \
  -d @recording.json
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/analyze` | POST | Analyze recording, return structured summary |
| `/api/report` | POST | Generate human-readable bug report |
| `/api/root-cause` | POST | Identify root cause of issues |
| `/api/reproduce` | POST | Generate curl commands to reproduce |
| `/api/health` | GET | Health check |

## Recording Schema

```json
{
  "sessionId": "session-123456-abc123",
  "startTime": 1712500000000,
  "endTime": 1712500060000,
  "url": "https://example.com/page",
  "clicks": [
    {
      "timestamp": 1712500010000,
      "x": 150,
      "y": 200,
      "targetTag": "BUTTON",
      "targetId": "submit-btn",
      "targetSelector": "#submit-btn"
    }
  ],
  "networkCalls": [
    {
      "timestamp": 1712500020000,
      "url": "https://api.example.com/data",
      "method": "POST",
      "status": 500,
      "requestBody": "{\"id\": 1}",
      "responseBody": "{\"error\": \"Internal Server Error\"}"
    }
  ],
  "consoleLogs": [
    {
      "timestamp": 1712500030000,
      "level": "error",
      "messages": ["API Error:", "Failed to fetch"]
    }
  ],
  "errors": [
    {
      "timestamp": 1712500040000,
      "message": "TypeError: Cannot read property 'x' of undefined",
      "stack": "at Function.x (app.js:123:45)"
    }
  ]
}
```

## Tech Stack

- **Backend**: Spring Boot 2.7.18
- **AI**: Claude Opus 4.6 (via completions.me - free, unlimited)
- **Extension**: Chrome Extension Manifest V3

## Free AI API

This project uses **completions.me** for free AI-powered analysis (no credit card required).

### Available Models
- Claude Opus 4.6 (best for coding/analysis)
- GPT-5.2
- Gemini 3.1 Pro
- Grok Code Fast

Get your free API key at: https://completions.me

## License

MIT

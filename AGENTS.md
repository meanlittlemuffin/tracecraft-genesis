# Session Replay Tool - Developer Documentation

## Project Overview

A hackathon prototype that records user browser sessions and uses AI to analyze recordings for debugging and issue detection.

### Features
- Records user clicks, network calls, console logs, and JavaScript errors
- Exports recordings as JSON files
- Sends recordings to a Spring Boot backend
- AI-powered analysis using Claude Opus 4.6 (completions.me API - free)
- Generates bug reports, root cause analysis, and reproduction steps

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Chrome Browser                            │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  Browser         │───▶│  Content Script │  (injected into    │
│  │  Extension      │    │  (popup.js)     │   every webpage)   │
│  │  (popup UI)     │    │                 │                    │
│  └────────┬────────┘    └─────────────────┘                    │
│           │                                                      │
│           │ chrome.storage.local                                │
│           ▼                                                      │
│  ┌─────────────────┐                                            │
│  │  Background     │                                            │
│  │  Script         │                                            │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘
           │
           │ HTTP POST /api/analyze
           ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Spring Boot Backend                          │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  Recording      │───▶│  AIService      │                    │
│  │  Controller     │    │  (REST calls)   │                    │
│  └─────────────────┘    └────────┬────────┘                    │
│                                  │                              │
│                                  ▼                              │
│                         ┌─────────────────┐                      │
│                         │  completions.me │                      │
│                         │  API            │                      │
│                         │  (Claude Opus   │                      │
│                         │   4.6 - Free)   │                      │
│                         └─────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
session-replay-tool/
├── backend/                          # Spring Boot Backend
│   ├── pom.xml                        # Maven dependencies
│   ├── mvnw / mvnw.cmd               # Maven wrapper scripts
│   ├── .env                          # API key storage
│   ├── error.log                      # Backend error logs
│   ├── output.log                     # Backend output logs
│   └── src/
│       └── main/
│           ├── java/com/hackathon/sessionreplay/
│           │   ├── SessionReplayApplication.java   # Main Spring Boot app
│           │   ├── api/
│           │   │   └── RecordingController.java    # REST API endpoints
│           │   └── service/
│           │       └── AIService.java          # AI API integration
│           └── resources/
│               ├── application.yml                 # App configuration
│               └── .env                            # Environment variables
│
├── browser-extension/                 # Chrome Extension (Manifest V3)
│   ├── manifest.json                  # Extension manifest
│   ├── icons/                        # Extension icons (16x16, 48x48, 128x128)
│   ├── popup/
│   │   ├── popup.html                # Extension UI HTML
│   │   ├── popup.css                 # Extension UI styles
│   │   └── popup.js                  # Extension UI logic
│   └── src/
│       ├── content-script.js         # Injected into webpages, captures events
│       └── background.js             # Service worker (not currently used)
│
├── first antropic claude api key.txt # API key (user's file)
└── README.md                         # User documentation
```

## Backend (Spring Boot)

### Technology Stack
- **Framework**: Spring Boot 2.7.18
- **Java Version**: JDK 15
- **Build Tool**: Maven 3.9.6
- **Dependencies**: spring-boot-starter-web, jackson

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/analyze` | POST | Analyze recording and get summary |
| `/api/report` | POST | Generate bug report |
| `/api/root-cause` | POST | Analyze root cause |
| `/api/reproduce` | POST | Generate reproduction steps |

### Request Format

```json
{
  "events": [
    { "type": "click", "selector": "#login-btn", "timestamp": "2026-04-08T10:00:00Z" },
    { "type": "network", "method": "GET", "url": "/api/user", "status": 500, "timestamp": "2026-04-08T10:00:01Z" },
    { "type": "console", "level": "error", "message": "Failed to fetch", "timestamp": "2026-04-08T10:00:01Z" },
    { "type": "error", "message": "TypeError: Cannot read property", "timestamp": "2026-04-08T10:00:02Z" }
  ],
  "url": "https://app.example.com/dashboard",
  "userAgent": "Mozilla/5.0..."
}
```

### Response Format

```json
{
  "analysis": "Summary of the recording analysis..."
}
```

### Configuration

Edit `backend/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: session-replay

completions:
  api-key: sk-cp-your-api-key-here
  base-url: https://completions.me/api/v1
  model: claude-opus-4.6

logging:
  level:
    root: INFO
```

## Browser Extension (Chrome Extension Manifest V3)

### Manifest Configuration

- **Version**: 3 (Manifest V3)
- **Permissions**: activeTab, storage, downloads
- **Host Permissions**: `<all_urls>` (runs on all websites)
- **Content Script**: Runs at `document_start` on all pages

### How It Works

1. **Content Script** (`content-script.js`)
   - Injected into every webpage
   - Adds click event listener to document
   - Responds to messages from popup
   - Captures: clicks, network calls, console logs, errors

2. **Popup UI** (`popup.js`)
   - User interface for the extension
   - Stores state in `chrome.storage.local`
   - Communicates with content script via `chrome.tabs.sendMessage`

3. **Data Flow**
   ```
   User clicks "Start Recording"
   → Popup sends 'startRecording' message to content script
   → Content script starts recording clicks
   → User interacts with webpage
   → Clicks are captured and stored locally
   → User clicks "Stop Recording"
   → Popup sends 'stopRecording' message
   → Content script returns recorded data
   → Data is saved to chrome.storage.local
   → User can export JSON or send to API
   ```

### Extension Buttons

| Button | Description |
|--------|-------------|
| Start Recording | Begin recording user interactions |
| Stop Recording | Stop recording and save data |
| Test Connection | Verify content script is loaded |
| Export JSON | Download recording as JSON file |
| Send to API | Send recording to backend for AI analysis |
| Clear | Clear all recorded data |

## Development Commands

### Backend

```bash
# Set JAVA_HOME (Windows)
set JAVA_HOME=C:\Program Files\Java\jdk-15.0.1

# Build the project
cd backend
mvn package -DskipTests

# Run the backend
java -jar target\session-replay-1.0.0.jar

# Or use Maven
mvn spring-boot:run
```

### Browser Extension

1. Go to `chrome://extensions/`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `browser-extension` folder
5. Click the extension icon to use
6. After making changes, click the refresh icon on the extension card

## Testing the Application

### Test 1: Backend Health Check

```bash
# Start the backend
cd backend
mvn spring-boot:run

# In another terminal, test health endpoint
curl http://localhost:8080/api/health

# Expected response: {"status":"UP"}
```

### Test 2: Backend AI Analysis

```bash
# Test the analyze endpoint with sample data
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {"type": "click", "selector": "#login-btn", "timestamp": "2026-04-08T10:00:00Z"},
      {"type": "network", "method": "GET", "url": "/api/user", "status": 500, "timestamp": "2026-04-08T10:00:01Z"},
      {"type": "error", "message": "TypeError: Cannot read property", "timestamp": "2026-04-08T10:00:02Z"}
    ],
    "url": "https://app.example.com/dashboard"
  }'
```

### Test 3: Full API Test Suite

```bash
# Test all endpoints
curl -X POST http://localhost:8080/api/report \
  -H "Content-Type: application/json" \
  -d '{"events":[{"type":"click","selector":"#btn"}],"url":"http://example.com"}'

curl -X POST http://localhost:8080/api/root-cause \
  -H "Content-Type: application/json" \
  -d '{"events":[{"type":"error","message":"NullPointerException"}],"url":"http://example.com"}'

curl -X POST http://localhost:8080/api/reproduce \
  -H "Content-Type: application/json" \
  -d '{"events":[{"type":"network","method":"POST","url":"/api/data","status":500}],"url":"http://example.com"}'
```

### Test 4: Chrome Extension

1. Open Chrome and navigate to `chrome://extensions/`
2. Enable "Developer mode" (top right toggle)
3. Click "Load unpacked" and select `browser-extension/` folder
4. Click the extension icon in the toolbar
5. Click **"Start Recording"**
6. Navigate to any website (e.g., https://example.com)
7. Click around the page to generate events
8. Click **"Stop Recording"**
9. Click **"Test Connection"** to verify content script is loaded
10. Click **"Export JSON"** to download the recording

### Test 5: End-to-End (Extension → Backend)

1. Start the backend: `cd backend && mvn spring-boot:run`
2. Open Chrome extension
3. Start recording and perform some actions
4. Click **"Send to API"** in the extension
5. Check the backend console for analysis output

### Expected Outputs

| Endpoint | Response |
|----------|----------|
| `/api/health` | `{"status": "UP"}` |
| `/api/analyze` | AI-generated summary with key events and potential issues |
| `/api/report` | Bug report with timeline, network activity, console logs |
| `/api/root-cause` | Root cause analysis with confidence percentage |
| `/api/reproduce` | curl commands to reproduce the issue |

## Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `COMPLETIONS_API_KEY` | completions.me API key (free) | Yes |

## Known Issues

1. **Page Navigation**: Recording state is lost when navigating to a different page (content script reloads)
   - Mitigation: Clicks are stored in `chrome.storage.local`
   
2. **API Key**: Use free completions.me API key (no credit card required)
   - Get one at: https://completions.me

3. **Java Version**: Project requires JDK 15 (not newer)
   - Spring Boot 2.7.x supports Java 15
   - Spring Boot 3.x requires Java 17+

## Debugging Tips

### Content Script Debugging
1. Right-click on webpage → Inspect → Console
2. Look for "Session Replay content script loaded" message
3. Check for "Click captured" messages when clicking

### Extension Popup Debugging
1. Right-click extension icon → Inspect Popup
2. Check Console for JavaScript errors

### Backend Debugging
1. Check `backend/error.log` and `backend/output.log`
2. Enable debug logging in `application.yml`

## Troubleshooting

### Extension not recording clicks
1. Click "Test Connection" button
2. Reload the webpage
3. Check browser console for errors
4. Ensure extension is enabled in chrome://extensions

### Backend not responding
1. Ensure Java 15 is installed and JAVA_HOME is set
2. Check if port 8080 is available
3. Verify API key is correct in application.yml

### API returns error
1. The completions.me API key may be invalid
2. Generate a new key at https://completions.me
3. Update the key in application.yml
4. Rebuild and restart the backend

## Free AI API Alternatives

This project uses completions.me for free AI-powered analysis. The service supports multiple providers through a unified OpenAI-compatible API.

### Available Providers

| Provider | Free Tier | Models | URL |
|----------|-----------|--------|-----|
| **completions.me** | Unlimited | Claude Opus 4.6, GPT-5.2, Gemini 3.1, Grok | completions.me |
| **Scitely** | Unlimited | DeepSeek, Qwen, Llama, Kimi | scitely.com |
| **Groq** | 30 RPM | Llama 3.3, DeepSeek R1, Qwen3 | groq.com |

### Changing the Model

To switch models in `application.yml`:

```yaml
completions:
  api-key: YOUR_API_KEY
  base-url: https://completions.me/api/v1
  model: gpt-5.2  # Change to: gpt-5.2, gemini-3.1-pro, grok-code-fast-1
```

### API Differences (Anthropic vs completions.me)

| Aspect | Old (Anthropic) | New (completions.me) |
|--------|-----------------|----------------------|
| Endpoint | `api.anthropic.com/v1/messages` | `completions.me/api/v1/chat/completions` |
| Auth Header | `x-api-key` | `Authorization: Bearer` |
| Model | Claude 3 Haiku | Claude Opus 4.6 |
| Cost | Paid | Free (unlimited) |

### Current Configuration

- **Provider**: completions.me
- **Model**: Claude Opus 4.6 (best for coding/analysis)
- **API Key**: Stored in `application.yml`

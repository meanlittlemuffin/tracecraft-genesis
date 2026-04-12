# AGENTS.md

## Project Overview

Bitbucket PR code reviewer using Claude AI. Receives webhooks from Bitbucket, fetches diffs, and posts review comments.

## Dev Commands

```bash
npm install
npm run dev    # Development with watch
npm start      # Production
```

## Project Structure

```
bitbucket-code-reviewer/
├── src/
│   ├── server.js    # Webhook endpoint
│   ├── bitbucket.js # Bitbucket API client
│   └── reviewer.js  # LLM integration
├── .env.example
└── README.md
```

## Key Conventions

- ES modules (`"type": "module"` in package.json)
- Environment variables in `.env` (copy from `.env.example`)
- Free tier LLM: Claude 3 Haiku

## Environment Setup

Required `.env` variables:
- `BITBUCKET_WORKSPACE`, `BITBUCKET_REPO`, `BITBUCKET_USERNAME`, `BITBUCKET_APP_PASSWORD`
- `CLAUDE_API_KEY`
- `PORT` (default: 3000)

## Architecture Notes

1. `server.js` receives webhooks → calls `fetchPRDiff` → calls `reviewDiff` → posts comments
2. `reviewer.js` chunks diffs and sends to Claude, returns structured comments
3. Inline comments use Bitbucket's `inline.path` and `inline.line` format

## Common Tasks

```bash
# Local testing with webhook exposure
ngrok http 3000
# Use ngrok URL in Bitbucket webhook settings

# Test manually
curl -X POST localhost:3000/webhook -H "Content-Type: application/json" -d '{"pullrequest": {"id": 1}}'
```

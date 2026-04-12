# Bitbucket Code Reviewer

Prototype that reviews Bitbucket PRs using Claude AI.

## Setup

```bash
cp .env.example .env
# Edit .env with your credentials
npm install
npm run dev
```

## Bitbucket Setup

1. Create an App Password: Bitbucket → Personal settings → App passwords
2. Add repository webhooks: Repository → Settings → Webhooks → Add webhook
   - URL: `https://your-server/webhook`
   - Events: Pull request created, Pull request updated

## Environment Variables

| Variable | Description |
|----------|-------------|
| BITBUCKET_WORKSPACE | Your Bitbucket workspace |
| BITBUCKET_REPO | Repository slug |
| BITBUCKET_USERNAME | Your Bitbucket username |
| BITBUCKET_APP_PASSWORD | App password (not your account password) |
| CLAUDE_API_KEY | API key from Anthropic (free tier available) |
| PORT | Server port (default: 3000) |

## Local Testing (No Bitbucket Required)

Test the reviewer directly with a diff:

```bash
curl -X POST http://localhost:3000/review \
  -H "Content-Type: application/json" \
  -d '{"diff": "diff --git a/test.js b/test.js\n--- a/test.js\n+++ b/test.js\n@@ -1 +1,4 @@\n const x = 1;\n+const y = 2;\n+const z = x + y;\n+console.log(z);\n"}'
```

Or use the sample diff:

```bash
curl -X POST http://localhost:3000/review \
  -H "Content-Type: application/json" \
  -d @test/sample.diff
```

## Quick Test (with webhooks)

```bash
curl -X POST http://localhost:3000/webhook \
  -H "Content-Type: application/json" \
  -d '{"pullrequest": {"id": 1}}'
```

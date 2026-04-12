# Quick Test Instructions

## Setup

```bash
cd bitbucket-code-reviewer
cp .env.example .env
# Add CLAUDE_API_KEY to .env
npm install
npm run dev
```

## Test Commands

### Basic test with inline diff:
```bash
curl -X POST http://localhost:3000/review \
  -H "Content-Type: application/json" \
  -d '{"diff": "diff --git a/test.js b/test.js\n--- a/test.js\n+++ b/test.js\n@@ -1 +1,4 @@\n const x = 1;\n+const y = x + 1;\n+const z = y * 2;\n+console.log(z);\n"}'
```

### Test with sample diff (SQL injection vuln):
```bash
curl -X POST http://localhost:3000/review \
  -H "Content-Type: application/json" \
  -d @test/sample.diff
```

### With Bitbucket (after setup):
```bash
# Get ngrok URL
ngrok http 3000

# Add webhook to Bitbucket repo, or test manually:
curl -X POST http://localhost:3000/webhook \
  -H "Content-Type: application/json" \
  -d '{"pullrequest": {"id": 1}}'
```

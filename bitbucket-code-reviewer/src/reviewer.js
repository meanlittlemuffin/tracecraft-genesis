import fetch from 'node-fetch';

const CLAUDE_URL = 'https://api.anthropic.com/v1/messages';
const CLAUDE_API_KEY = process.env.CLAUDE_API_KEY;

const REVIEW_PROMPT = `You are a code reviewer. Review the following code diff and provide feedback.

Focus on:
- Security vulnerabilities
- Performance issues
- Code clarity and maintainability
- Potential bugs
- Best practices

Format your response as a JSON array of comments:
[
  {
    "file": "path/to/file.js",
    "line": 42,
    "lineType": "ADDED",
    "severity": "warning",
    "text": "Description of the issue"
  }
]

If no issues found, return an empty array: []

DIFF:
`;

function chunkDiff(diff, maxChars = 8000) {
  const chunks = [];
  const lines = diff.split('\n');
  let currentChunk = [];
  let currentSize = 0;

  for (const line of lines) {
    if (currentSize + line.length > maxChars) {
      chunks.push(currentChunk.join('\n'));
      currentChunk = [line];
      currentSize = line.length;
    } else {
      currentChunk.push(line);
      currentSize += line.length;
    }
  }

  if (currentChunk.length) {
    chunks.push(currentChunk.join('\n'));
  }

  return chunks;
}

function parseDiffLine(line) {
  const match = line.match(/^\+\+\+ b\/(.+)$/);
  if (match) return { type: 'file', path: match[1] };

  const addedMatch = line.match(/^@@ -\d+(?:,\d+)? \+\d+(?:,(\d+))? @@/);
  if (addedMatch) return { type: 'hunk', lines: parseInt(addedMatch[1]) || 1 };

  return null;
}

export async function reviewDiff(diff) {
  const chunks = chunkDiff(diff);
  const allComments = [];

  for (const chunk of chunks) {
    const comment = await reviewChunk(chunk);
    allComments.push(...comment);
  }

  return allComments;
}

async function reviewChunk(chunk) {
  const response = await fetch(CLAUDE_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': CLAUDE_API_KEY,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true'
    },
    body: JSON.stringify({
      model: 'claude-3-haiku-20240307',
      max_tokens: 1024,
      messages: [
        {
          role: 'user',
          content: REVIEW_PROMPT + chunk
        }
      ]
    })
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Claude API error: ${response.status} - ${error}`);
  }

  const data = await response.json();
  const text = data.content?.[0]?.text || '[]';

  try {
    return JSON.parse(text);
  } catch {
    return [{
      text: text,
      severity: 'info'
    }];
  }
}

import fetch from 'node-fetch';
import crypto from 'crypto';

const WORKSPACE = process.env.BITBUCKET_WORKSPACE;
const REPO = process.env.BITBUCKET_REPO;
const USERNAME = process.env.BITBUCKET_USERNAME;
const APP_PASSWORD = process.env.BITBUCKET_APP_PASSWORD;

function auth() {
  const token = Buffer.from(`${USERNAME}:${APP_PASSWORD}`).toString('base64');
  return `Basic ${token}`;
}

export async function fetchPRDiff(prId) {
  const url = `https://api.bitbucket.org/2.0/repositories/${WORKSPACE}/${REPO}/pullrequests/${prId}/diff`;

  const response = await fetch(url, {
    headers: {
      Authorization: auth(),
      Accept: 'text/plain'
    }
  });

  if (!response.ok) {
    throw new Error(`Bitbucket API error: ${response.status}`);
  }

  return response.text();
}

export async function postComment(prId, comment) {
  const url = `https://api.bitbucket.org/2.0/repositories/${WORKSPACE}/${REPO}/pullrequests/${prId}/comments`;

  const body = {
    content: {
      raw: comment.text
    },
    inline: comment.line ? {
      path: comment.file,
      line: comment.line,
      line_type: comment.lineType || 'ADDED'
    } : undefined
  };

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: auth(),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    throw new Error(`Failed to post comment: ${response.status}`);
  }

  return response.json();
}

import express from 'express';
import { fetchPRDiff, postComment } from './bitbucket.js';
import { reviewDiff } from './reviewer.js';

const app = express();
app.use(express.json());

app.post('/webhook', async (req, res) => {
  const { pullrequest, repository } = req.body;

  if (!pullrequest) {
    return res.status(400).send('Missing pullrequest');
  }

  console.log(`Reviewing PR #${pullrequest.id}: ${pullrequest.title}`);

  try {
    const diff = await fetchPRDiff(pullrequest.id);
    const comments = await reviewDiff(diff);

    for (const comment of comments) {
      await postComment(pullrequest.id, comment);
    }

    res.json({ success: true, commentsPosted: comments.length });
  } catch (error) {
    console.error('Review failed:', error);
    res.status(500).json({ error: error.message });
  }
});

app.get('/health', (req, res) => res.json({ status: 'ok' }));

app.post('/review', async (req, res) => {
  const { diff } = req.body;

  if (!diff) {
    return res.status(400).json({ error: 'Missing diff in body' });
  }

  console.log('Reviewing local diff...');

  try {
    const comments = await reviewDiff(diff);
    res.json({ success: true, comments });
  } catch (error) {
    console.error('Review failed:', error);
    res.status(500).json({ error: error.message });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});

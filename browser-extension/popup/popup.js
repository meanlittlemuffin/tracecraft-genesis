let isRecording = false;
let clicks = [];
let recordingData = null;

const statusEl = document.getElementById('status');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const testBtn = document.getElementById('testBtn');
const exportBtn = document.getElementById('exportBtn');
const sendApiBtn = document.getElementById('sendApiBtn');
const clearBtn = document.getElementById('clearBtn');
const clickCountEl = document.getElementById('clickCount');
const networkCountEl = document.getElementById('networkCount');
const errorCountEl = document.getElementById('errorCount');
const consoleCountEl = document.getElementById('consoleCount');
const apiStatusEl = document.getElementById('apiStatus');
const previewEl = document.getElementById('preview');
const previewDataEl = document.getElementById('previewData');

const API_URL = 'http://localhost:8080/api';

async function init() {
    const result = await chrome.storage.local.get(['isRecording', 'clicks', 'networkCalls', 'consoleLogs', 'errors', 'recordingData']);
    if (result.isRecording) {
        isRecording = true;
    }
    if (result.clicks) {
        clicks = result.clicks;
    }
    if (result.recordingData) {
        recordingData = result.recordingData;
    }
    updateUI();
    updateStats();
}

function updateUI() {
    if (isRecording) {
        statusEl.textContent = 'Recording...';
        startBtn.disabled = true;
        stopBtn.disabled = false;
        exportBtn.disabled = true;
        sendApiBtn.disabled = true;
        clearBtn.disabled = true;
    } else {
        statusEl.textContent = recordingData || clicks.length > 0 ? 'Has Recording' : 'Ready';
        startBtn.disabled = false;
        stopBtn.disabled = true;
        exportBtn.disabled = !recordingData && clicks.length === 0;
        sendApiBtn.disabled = !recordingData && clicks.length === 0;
        clearBtn.disabled = clicks.length === 0 && !recordingData;
    }
}

function updateStats() {
    const totalClicks = clicks.length;
    clickCountEl.textContent = totalClicks;
    networkCountEl.textContent = recordingData?.networkCalls?.length || 0;
    errorCountEl.textContent = recordingData?.errors?.length || 0;
    consoleCountEl.textContent = recordingData?.consoleLogs?.length || 0;
    
    if (totalClicks > 0 || recordingData?.networkCalls?.length > 0 || recordingData?.errors?.length > 0 || recordingData?.consoleLogs?.length > 0) {
        previewEl.style.display = 'block';
        const data = { clicks: clicks, recordingData: recordingData };
        previewDataEl.textContent = JSON.stringify(data, null, 2);
    }
}

testBtn.addEventListener('click', async () => {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    chrome.tabs.sendMessage(tab.id, { action: 'getClicks' }, (response) => {
        if (chrome.runtime.lastError) {
            apiStatusEl.textContent = 'Error: ' + chrome.runtime.lastError.message;
            return;
        }
        if (response) {
            apiStatusEl.textContent = `Connected - Recording: ${response.isRecording}, Clicks: ${response.clicks?.length || 0}, Network: ${response.networkCalls?.length || 0}, Console: ${response.consoleLogs?.length || 0}, Errors: ${response.errors?.length || 0}`;
        }
    });
});

startBtn.addEventListener('click', async () => {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    chrome.tabs.sendMessage(tab.id, { action: 'startRecording' }, (response) => {
        if (chrome.runtime.lastError) {
            apiStatusEl.textContent = 'Error: ' + chrome.runtime.lastError.message;
            return;
        }
        if (response?.success) {
            isRecording = true;
            clicks = [];
            recordingData = null;
            chrome.storage.local.set({ isRecording: true, clicks: [], networkCalls: [], consoleLogs: [], errors: [] });
            updateUI();
            apiStatusEl.textContent = 'Recording started!';
        }
    });
});

stopBtn.addEventListener('click', async () => {
    const result = await chrome.storage.local.get(['clicks', 'networkCalls', 'consoleLogs', 'errors']);
    
    isRecording = false;
    const storedClicks = result.clicks || [];
    const storedNetwork = result.networkCalls || [];
    const storedConsole = result.consoleLogs || [];
    const storedErrors = result.errors || [];
    
    clicks = storedClicks;
    recordingData = {
        clicks: clicks,
        networkCalls: storedNetwork,
        consoleLogs: storedConsole,
        errors: storedErrors,
        stoppedAt: new Date().toISOString()
    };
    
    await chrome.storage.local.set({ isRecording: false, recordingData: recordingData });
    
    updateStats();
    updateUI();
    apiStatusEl.textContent = `Stopped! Clicks: ${clicks.length}, Network: ${storedNetwork.length}, Console: ${storedConsole.length}, Errors: ${storedErrors.length}`;
});

exportBtn.addEventListener('click', () => {
    if (!recordingData && clicks.length === 0) return;
    const data = {
        clicks: clicks,
        networkCalls: recordingData?.networkCalls || [],
        consoleLogs: recordingData?.consoleLogs || [],
        errors: recordingData?.errors || [],
        url: recordingData?.url || '',
        exportedAt: new Date().toISOString()
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    chrome.downloads.download({ url: URL.createObjectURL(blob), filename: `recording-${Date.now()}.json`, saveAs: true });
});

sendApiBtn.addEventListener('click', async () => {
    if (!recordingData && clicks.length === 0) return;
    
    const events = [
        ...clicks.map(c => ({ type: 'click', selector: c.tag + (c.id ? '#' + c.id : ''), timestamp: new Date(c.timestamp).toISOString() })),
        ...(recordingData?.networkCalls || []).map(n => ({ type: 'network', method: n.method, url: n.url, status: n.status, timestamp: new Date(n.timestamp).toISOString() })),
        ...(recordingData?.consoleLogs || []).map(c => ({ type: 'console', level: c.level, message: c.message, timestamp: new Date(c.timestamp).toISOString() })),
        ...(recordingData?.errors || []).map(e => ({ type: 'error', message: e.message, timestamp: new Date(e.timestamp).toISOString() }))
    ];
    
    const data = {
        events: events,
        url: recordingData?.url || '',
        userAgent: navigator.userAgent
    };

    sendApiBtn.disabled = true;
    apiStatusEl.textContent = 'Sending...';

    try {
        const response = await fetch(`${API_URL}/analyze`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        const result = await response.json();
        apiStatusEl.textContent = 'Analysis complete!';
        const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' });
        chrome.downloads.download({ url: URL.createObjectURL(blob), filename: `analysis-${Date.now()}.json`, saveAs: true });
    } catch (error) {
        apiStatusEl.textContent = 'Error: ' + error.message;
    }
    sendApiBtn.disabled = false;
});

clearBtn.addEventListener('click', async () => {
    clicks = [];
    recordingData = null;
    isRecording = false;
    await chrome.storage.local.clear();
    previewEl.style.display = 'none';
    updateStats();
    updateUI();
    apiStatusEl.textContent = '';
});

init();

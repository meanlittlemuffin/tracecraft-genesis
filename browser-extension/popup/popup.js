/**
 * TraceCraft Genesis — Popup UI
 */

const API_URL = 'http://localhost:8080/api';

// DOM elements
const statusEl = document.getElementById('status');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const exportBtn = document.getElementById('exportBtn');
const analyzeBtn = document.getElementById('analyzeBtn');
const networkBtn = document.getElementById('networkBtn');
const bugBtn = document.getElementById('bugBtn');
const codeFixBtn = document.getElementById('codeFixBtn');
const clearBtn = document.getElementById('clearBtn');
const actionButtons = document.getElementById('actionButtons');
const apiStatusEl = document.getElementById('apiStatus');
const previewEl = document.getElementById('preview');
const previewDataEl = document.getElementById('previewData');
const vitalsEl = document.getElementById('vitals');

// Stat counters
const counters = {
    networkCount: document.getElementById('networkCount'),
    clickCount: document.getElementById('clickCount'),
    errorCount: document.getElementById('errorCount'),
    consoleCount: document.getElementById('consoleCount'),
    navCount: document.getElementById('navCount'),
    keyCount: document.getElementById('keyCount'),
    scrollCount: document.getElementById('scrollCount'),
    longTaskCount: document.getElementById('longTaskCount')
};

// Vital displays
const vitalEls = {
    lcp: document.getElementById('lcpValue'),
    cls: document.getElementById('clsValue'),
    inp: document.getElementById('inpValue'),
    ttfb: document.getElementById('ttfbValue')
};

let isRecording = false;
let sessionData = null;
let pollInterval = null;
let codeFixReadySessionKey = '';

// ──────────────────────────────────────────────
//  INIT
// ──────────────────────────────────────────────

async function init() {
    const result = await chrome.storage.local.get(['isRecording', 'sessionData', 'codeFixReadySessionKey']);
    isRecording = result.isRecording || false;
    sessionData = result.sessionData || null;
    codeFixReadySessionKey = result.codeFixReadySessionKey || '';
    updateUI();
    if (sessionData) updateStats(sessionData);
    if (isRecording) startPolling();
}

// ──────────────────────────────────────────────
//  UI STATE
// ──────────────────────────────────────────────

function updateUI() {
    if (isRecording) {
        statusEl.textContent = 'Recording...';
        statusEl.className = 'status recording';
        startBtn.disabled = true;
        stopBtn.disabled = false;
        actionButtons.style.display = 'none';
    } else {
        const hasData = sessionData && (
            sessionData.clicks.length > 0 ||
            sessionData.networkCalls.length > 0 ||
            sessionData.errors.length > 0
        );
        const hasCodeFix = hasData && codeFixReadySessionKey === getSessionKey(sessionData);
        statusEl.textContent = hasData ? 'Recording Available' : 'Ready';
        statusEl.className = hasData ? 'status has-data' : 'status';
        startBtn.disabled = false;
        stopBtn.disabled = true;
        actionButtons.style.display = hasData ? 'flex' : 'none';
        codeFixBtn.disabled = !hasCodeFix;
    }
}

function updateStats(data) {
    if (!data) return;
    counters.networkCount.textContent = data.networkCalls ? data.networkCalls.length : 0;
    counters.clickCount.textContent = data.clicks ? data.clicks.length : 0;
    counters.errorCount.textContent = data.errors ? data.errors.length : 0;
    counters.consoleCount.textContent = data.consoleLogs ? data.consoleLogs.length : 0;
    counters.navCount.textContent = data.navigations ? data.navigations.length : 0;
    counters.keyCount.textContent = data.keyboardEvents ? data.keyboardEvents.length : 0;
    counters.scrollCount.textContent = data.scrollEvents ? data.scrollEvents.length : 0;
    counters.longTaskCount.textContent = data.longTasks ? data.longTasks.length : 0;

    // Web Vitals
    if (data.webVitals && Object.keys(data.webVitals).length > 0) {
        vitalsEl.style.display = 'block';
        if (data.webVitals.lcp) {
            const v = data.webVitals.lcp.value;
            vitalEls.lcp.textContent = v + 'ms';
            vitalEls.lcp.className = 'vital-value ' + (v <= 2500 ? 'good' : v <= 4000 ? 'needs-improvement' : 'poor');
        }
        if (data.webVitals.cls) {
            const v = data.webVitals.cls.value;
            vitalEls.cls.textContent = v;
            vitalEls.cls.className = 'vital-value ' + (v <= 0.1 ? 'good' : v <= 0.25 ? 'needs-improvement' : 'poor');
        }
        if (data.webVitals.inp) {
            const v = data.webVitals.inp.value;
            vitalEls.inp.textContent = v + 'ms';
            vitalEls.inp.className = 'vital-value ' + (v <= 200 ? 'good' : v <= 500 ? 'needs-improvement' : 'poor');
        }
        if (data.webVitals.ttfb) {
            const v = data.webVitals.ttfb.value;
            vitalEls.ttfb.textContent = v + 'ms';
            vitalEls.ttfb.className = 'vital-value ' + (v <= 800 ? 'good' : v <= 1800 ? 'needs-improvement' : 'poor');
        }
    }

    // Show summary preview
    const totalEvents = (data.clicks || []).length + (data.networkCalls || []).length +
        (data.errors || []).length + (data.consoleLogs || []).length;
    if (totalEvents > 0) {
        previewEl.style.display = 'block';
        const summary = {
            totalEvents: totalEvents,
            duration: data.metadata && data.metadata.startTime
                ? Math.round((Date.now() - data.metadata.startTime) / 1000) + 's'
                : '-',
            topNetworkCalls: (data.networkCalls || []).slice(-5).map(function (n) {
                return n.method + ' ' + (n.url || '').substring(0, 60) + ' → ' + n.status + ' (' + n.duration + 'ms)';
            }),
            recentErrors: (data.errors || []).slice(-3).map(function (e) {
                return (e.type || 'error') + ': ' + (e.message || '').substring(0, 80);
            })
        };
        previewDataEl.textContent = JSON.stringify(summary, null, 2);
    }
}

// ──────────────────────────────────────────────
//  POLLING (live updates while recording)
// ──────────────────────────────────────────────

function startPolling() {
    if (pollInterval) return;
    pollInterval = setInterval(async function () {
        const result = await chrome.storage.local.get(['sessionData']);
        if (result.sessionData) {
            sessionData = result.sessionData;
            updateStats(sessionData);
        }
    }, 1500);
}

function stopPolling() {
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
    }
}

// ──────────────────────────────────────────────
//  RECORDING CONTROLS
// ──────────────────────────────────────────────

startBtn.addEventListener('click', async function () {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    chrome.tabs.sendMessage(tab.id, { action: 'startRecording' }, function (response) {
        if (chrome.runtime.lastError) {
            showStatus('Error: ' + chrome.runtime.lastError.message + '. Reload the page and try again.', 'error');
            return;
        }
        if (response && response.success) {
            isRecording = true;
            sessionData = null;
            codeFixReadySessionKey = '';
            chrome.storage.local.remove('codeFixReadySessionKey');
            updateUI();
            resetStats();
            startPolling();
            showStatus('Recording started!', 'success');
        }
    });
});

stopBtn.addEventListener('click', async function () {
    stopPolling();
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    chrome.tabs.sendMessage(tab.id, { action: 'stopRecording' }, function (response) {
        if (chrome.runtime.lastError) {
            // Fallback: read directly from storage
            chrome.storage.local.get(['sessionData'], function (result) {
                isRecording = false;
                sessionData = result.sessionData || sessionData;
                if (sessionData) sessionData.metadata.stopTime = Date.now();
                chrome.storage.local.set({ isRecording: false, sessionData: sessionData });
                updateUI();
                if (sessionData) updateStats(sessionData);
                showStatus('Stopped (recovered from storage)', 'success');
            });
            return;
        }
        isRecording = false;
        if (response && response.data) {
            sessionData = response.data;
        }
        updateUI();
        if (sessionData) updateStats(sessionData);
        showStatus(buildStopMessage(), 'success');
    });
});

function buildStopMessage() {
    if (!sessionData) return 'Stopped.';
    const n = sessionData.networkCalls ? sessionData.networkCalls.length : 0;
    const c = sessionData.clicks ? sessionData.clicks.length : 0;
    const e = sessionData.errors ? sessionData.errors.length : 0;
    return 'Stopped! ' + n + ' network, ' + c + ' clicks, ' + e + ' errors';
}

// ──────────────────────────────────────────────
//  EXPORT
// ──────────────────────────────────────────────

exportBtn.addEventListener('click', function () {
    if (!sessionData) return;
    const blob = new Blob([JSON.stringify(sessionData, null, 2)], { type: 'application/json' });
    chrome.downloads.download({
        url: URL.createObjectURL(blob),
        filename: 'tracecraft-recording-' + Date.now() + '.json',
        saveAs: true
    });
});

// ──────────────────────────────────────────────
//  ANALYSIS BUTTONS
// ──────────────────────────────────────────────

analyzeBtn.addEventListener('click', function () { sendToApi('/analyze', 'Full Analysis'); });
networkBtn.addEventListener('click', function () { sendToApi('/network-bottlenecks', 'Network Bottleneck Analysis'); });
bugBtn.addEventListener('click', function () { sendToApi('/bug-diagnosis', 'Bug Diagnosis'); });
codeFixBtn.addEventListener('click', function () { sendToApi('/code-fix-suggestion', 'Code Fix Suggestion'); });

async function sendToApi(endpoint, label) {
    if (!sessionData) return;

    // Build the payload — include all raw data for AI analysis
    const payload = buildPayload();

    setAnalysisButtonsDisabled(true);
    showStatus('Sending to ' + label + '...', 'sending');

    try {
        const response = await fetch(API_URL + endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        var result;
        try {
            result = await response.json();
        } catch (_) {
            showStatus('HTTP ' + response.status + ': ' + response.statusText, 'error');
            return;
        }

        if (!response.ok) {
            var errMsg = result.error || ('HTTP ' + response.status + ': ' + response.statusText);
            showStatus(errMsg, 'error');
            return;
        }

        if (endpoint === '/bug-diagnosis') {
            codeFixReadySessionKey = getSessionKey(sessionData);
            chrome.storage.local.set({ codeFixReadySessionKey: codeFixReadySessionKey });
            updateUI();
        }

        showStatus(label + ' complete!', 'success');

        // Download the analysis result
        const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' });
        const downloadUrl = URL.createObjectURL(blob);
        chrome.downloads.download({
            url: downloadUrl,
            filename: 'tracecraft-' + endpoint.replace('/', '') + '-' + Date.now() + '.json',
            saveAs: true
        }, function () { URL.revokeObjectURL(downloadUrl); });
    } catch (error) {
        showStatus('Error: ' + error.message, 'error');
    } finally {
        setAnalysisButtonsDisabled(false);
    }
}

function buildPayload() {
    // Build unified event timeline for AI
    const events = [];

    (sessionData.clicks || []).forEach(function (c) {
        events.push({
            type: 'click',
            timestamp: c.timestamp,
            selector: c.target ? c.target.cssSelector : '',
            element: c.target ? (c.target.tag + (c.target.id ? '#' + c.target.id : '')) : '',
            text: c.target ? c.target.text : '',
            pageUrl: c.pageUrl
        });
    });

    (sessionData.networkCalls || []).forEach(function (n) {
        events.push({
            type: 'network',
            timestamp: n.timestamp,
            method: n.method,
            url: n.url,
            status: n.status,
            statusText: n.statusText,
            duration: n.duration,
            requestHeaders: n.requestHeaders,
            requestBody: n.requestBody,
            responseHeaders: n.responseHeaders,
            responseBody: n.responseBody,
            responseSize: n.responseSize,
            queryParams: n.queryParams,
            error: n.error,
            initiatorType: n.initiatorType,
            pageUrl: n.pageUrl
        });
    });

    (sessionData.consoleLogs || []).forEach(function (c) {
        events.push({
            type: 'console',
            timestamp: c.timestamp,
            level: c.level,
            message: c.message,
            pageUrl: c.pageUrl
        });
    });

    (sessionData.errors || []).forEach(function (e) {
        events.push({
            type: 'error',
            timestamp: e.timestamp,
            errorType: e.type,
            message: e.message,
            filename: e.filename,
            stack: e.stack,
            pageUrl: e.pageUrl
        });
    });

    (sessionData.navigations || []).forEach(function (n) {
        events.push({
            type: 'navigation',
            timestamp: n.timestamp,
            navigationType: n.type,
            url: n.url,
            pageUrl: n.pageUrl
        });
    });

    (sessionData.formSubmissions || []).forEach(function (f) {
        events.push({
            type: 'form_submission',
            timestamp: f.timestamp,
            action: f.action,
            method: f.method,
            fieldCount: f.fieldCount,
            pageUrl: f.pageUrl
        });
    });

    // Sort by timestamp
    events.sort(function (a, b) { return a.timestamp - b.timestamp; });

    // Pre-compute rage clicks
    const rageClicks = detectRageClicks(sessionData.clicks || []);

    return {
        events: events,
        url: sessionData.metadata ? sessionData.metadata.startUrl : '',
        userAgent: sessionData.metadata ? sessionData.metadata.userAgent : navigator.userAgent,
        metadata: sessionData.metadata,
        webVitals: sessionData.webVitals,
        longTasks: sessionData.longTasks,
        performanceTimings: sessionData.performanceTimings,
        memorySnapshots: sessionData.memorySnapshots,
        scrollEvents: sessionData.scrollEvents,
        keyboardEvents: sessionData.keyboardEvents,
        rageClicks: rageClicks,
        stats: {
            totalEvents: events.length,
            networkCalls: (sessionData.networkCalls || []).length,
            clicks: (sessionData.clicks || []).length,
            errors: (sessionData.errors || []).length,
            consoleLogs: (sessionData.consoleLogs || []).length,
            navigations: (sessionData.navigations || []).length,
            formSubmissions: (sessionData.formSubmissions || []).length,
            scrollEvents: (sessionData.scrollEvents || []).length,
            keyboardEvents: (sessionData.keyboardEvents || []).length,
            longTasks: (sessionData.longTasks || []).length
        }
    };
}

// ──────────────────────────────────────────────
//  RAGE CLICK PRE-COMPUTATION
// ──────────────────────────────────────────────

function detectRageClicks(clicks) {
    const rageClicks = [];
    for (let i = 0; i < clicks.length; i++) {
        const cluster = [clicks[i]];
        for (let j = i + 1; j < clicks.length; j++) {
            if (clicks[j].timestamp - clicks[i].timestamp <= 1000 &&
                Math.abs(clicks[j].x - clicks[i].x) < 40 &&
                Math.abs(clicks[j].y - clicks[i].y) < 40) {
                cluster.push(clicks[j]);
            } else {
                break;
            }
        }
        if (cluster.length >= 3) {
            rageClicks.push({
                element: cluster[0].target ? cluster[0].target.cssSelector : 'unknown',
                elementTag: cluster[0].target ? cluster[0].target.tag : '',
                elementText: cluster[0].target ? cluster[0].target.text : '',
                clickCount: cluster.length,
                windowMs: cluster[cluster.length - 1].timestamp - cluster[0].timestamp,
                pageUrl: cluster[0].pageUrl,
                startTimestamp: cluster[0].timestamp
            });
            i += cluster.length - 1; // skip past the cluster
        }
    }
    return rageClicks;
}

function getSessionKey(data) {
    if (!data || !data.metadata) return '';
    return [
        data.metadata.startTime || '',
        data.metadata.startUrl || '',
        (data.networkCalls || []).length,
        (data.clicks || []).length,
        (data.errors || []).length
    ].join('|');
}

// ──────────────────────────────────────────────
//  CLEAR
// ──────────────────────────────────────────────

clearBtn.addEventListener('click', async function () {
    sessionData = null;
    isRecording = false;
    codeFixReadySessionKey = '';
    await chrome.storage.local.clear();
    resetStats();
    updateUI();
    vitalsEl.style.display = 'none';
    previewEl.style.display = 'none';
    apiStatusEl.className = 'api-status';
    apiStatusEl.textContent = '';
});

// ──────────────────────────────────────────────
//  HELPERS
// ──────────────────────────────────────────────

function resetStats() {
    Object.values(counters).forEach(function (el) { el.textContent = '0'; });
    Object.values(vitalEls).forEach(function (el) {
        el.textContent = '-';
        el.className = 'vital-value';
    });
}

function showStatus(message, type) {
    apiStatusEl.textContent = message;
    apiStatusEl.className = 'api-status visible ' + type;
}

function setAnalysisButtonsDisabled(disabled) {
    analyzeBtn.disabled = disabled;
    networkBtn.disabled = disabled;
    bugBtn.disabled = disabled;
    exportBtn.disabled = disabled;
    codeFixBtn.disabled = disabled || codeFixReadySessionKey !== getSessionKey(sessionData);
}

// Start
init();

(function() {
    'use strict';

    let isRecording = false;
    let clicks = [];
    let networkCalls = [];
    let consoleLogs = [];
    let errors = [];
    let startTime = null;

    function loadFromStorage() {
        chrome.storage.local.get(['isRecording', 'clicks', 'networkCalls', 'consoleLogs', 'errors'], (result) => {
            if (result.isRecording) {
                isRecording = true;
                clicks = result.clicks || [];
                networkCalls = result.networkCalls || [];
                consoleLogs = result.consoleLogs || [];
                errors = result.errors || [];
                console.log('Session Replay: Resumed recording from storage. Clicks so far:', clicks.length);
            }
        });
    }

    loadFromStorage();

    const originalFetch = window.fetch;
    window.fetch = async function(...args) {
        if (isRecording) {
            const start = Date.now();
            try {
                const response = await originalFetch.apply(this, args);
                const call = {
                    timestamp: Date.now(),
                    type: 'fetch',
                    method: args[0]?.method || 'GET',
                    url: args[0]?.url || args[0] || '',
                    status: response.status,
                    duration: Date.now() - start,
                    url: window.location.href
                };
                networkCalls.push(call);
                chrome.storage.local.set({ networkCalls: networkCalls });
                return response;
            } catch (error) {
                const call = {
                    timestamp: Date.now(),
                    type: 'fetch',
                    method: args[0]?.method || 'GET',
                    url: args[0]?.url || args[0] || '',
                    status: 0,
                    error: error.message,
                    duration: Date.now() - start,
                    url: window.location.href
                };
                networkCalls.push(call);
                chrome.storage.local.set({ networkCalls: networkCalls });
                throw error;
            }
        }
        return originalFetch.apply(this, args);
    };

    const originalXHROpen = XMLHttpRequest.prototype.open;
    const originalXHRSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this._method = method;
        this._url = url;
        this._startTime = Date.now();
        originalXHROpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
        if (isRecording) {
            this.addEventListener('load', () => {
                const call = {
                    timestamp: Date.now(),
                    type: 'xhr',
                    method: this._method,
                    url: this._url,
                    status: this.status,
                    duration: Date.now() - this._startTime,
                    url: window.location.href
                };
                networkCalls.push(call);
                chrome.storage.local.set({ networkCalls: networkCalls });
            });
            this.addEventListener('error', () => {
                const call = {
                    timestamp: Date.now(),
                    type: 'xhr',
                    method: this._method,
                    url: this._url,
                    status: 0,
                    error: 'Network error',
                    duration: Date.now() - this._startTime,
                    url: window.location.href
                };
                networkCalls.push(call);
                chrome.storage.local.set({ networkCalls: networkCalls });
            });
        }
        originalXHRSend.apply(this, arguments);
    };

    const originalConsole = {
        log: console.log,
        error: console.error,
        warn: console.warn,
        info: console.info
    };
    console.log = function(...args) {
        if (isRecording) {
            const entry = { timestamp: Date.now(), level: 'log', message: args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '), url: window.location.href };
            consoleLogs.push(entry);
            chrome.storage.local.set({ consoleLogs: consoleLogs });
        }
        originalConsole.log.apply(console, args);
    };
    console.error = function(...args) {
        if (isRecording) {
            const entry = { timestamp: Date.now(), level: 'error', message: args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '), url: window.location.href };
            consoleLogs.push(entry);
            chrome.storage.local.set({ consoleLogs: consoleLogs });
        }
        originalConsole.error.apply(console, args);
    };
    console.warn = function(...args) {
        if (isRecording) {
            const entry = { timestamp: Date.now(), level: 'warn', message: args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '), url: window.location.href };
            consoleLogs.push(entry);
            chrome.storage.local.set({ consoleLogs: consoleLogs });
        }
        originalConsole.warn.apply(console, args);
    };
    console.info = function(...args) {
        if (isRecording) {
            const entry = { timestamp: Date.now(), level: 'info', message: args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '), url: window.location.href };
            consoleLogs.push(entry);
            chrome.storage.local.set({ consoleLogs: consoleLogs });
        }
        originalConsole.info.apply(console, args);
    };

    window.onerror = function(message, source, lineno, colno, error) {
        if (isRecording) {
            const entry = {
                timestamp: Date.now(),
                message: message,
                source: source,
                line: lineno,
                column: colno,
                stack: error?.stack || '',
                url: window.location.href
            };
            errors.push(entry);
            chrome.storage.local.set({ errors: errors });
        }
        return false;
    };

    window.addEventListener('unhandledrejection', function(event) {
        if (isRecording) {
            const entry = {
                timestamp: Date.now(),
                message: 'Unhandled Promise Rejection: ' + event.reason,
                source: '',
                line: 0,
                column: 0,
                stack: event.reason?.stack || '',
                url: window.location.href
            };
            errors.push(entry);
            chrome.storage.local.set({ errors: errors });
        }
    });

    function captureClick(event) {
        if (!isRecording) return;
        const target = event.target;
        const click = {
            timestamp: Date.now(),
            x: event.clientX,
            y: event.clientY,
            tag: target.tagName,
            id: target.id || '',
            className: target.className || '',
            text: target.innerText?.substring(0, 50) || '',
            url: window.location.href
        };
        clicks.push(click);
        chrome.storage.local.set({ clicks: clicks });
        console.log('Click captured. Total:', clicks.length);
    }

    document.addEventListener('click', captureClick, true);

    chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
        if (message.action === 'startRecording') {
            isRecording = true;
            clicks = [];
            networkCalls = [];
            consoleLogs = [];
            errors = [];
            startTime = Date.now();
            chrome.storage.local.set({ isRecording: true, clicks: [], networkCalls: [], consoleLogs: [], errors: [] });
            console.log('Recording started');
            sendResponse({ success: true, clicks: 0 });
        } 
        else if (message.action === 'stopRecording') {
            isRecording = false;
            chrome.storage.local.set({ isRecording: false });
            console.log('Recording stopped. Clicks:', clicks.length, 'Network:', networkCalls.length, 'Console:', consoleLogs.length, 'Errors:', errors.length);
            sendResponse({ 
                success: true, 
                data: {
                    clicks: clicks,
                    networkCalls: networkCalls,
                    consoleLogs: consoleLogs,
                    errors: errors,
                    url: window.location.href
                }
            });
        } 
        else if (message.action === 'getClicks') {
            sendResponse({ clicks: clicks, networkCalls: networkCalls, consoleLogs: consoleLogs, errors: errors, isRecording: isRecording });
        }
        else if (message.action === 'getRecordingState') {
            sendResponse({ isRecording: isRecording, clicks: clicks, networkCalls: networkCalls, consoleLogs: consoleLogs, errors: errors });
        }
        return false;
    });

    console.log('Session Replay content script loaded');
})();

/**
 * TraceCraft Genesis — Isolated world content script
 *
 * Runs in Chrome's isolated world. Responsibilities:
 * - Receives intercepted data from MAIN world page-interceptor.js via window.postMessage
 * - Captures user interactions (clicks, keyboard, scroll, forms, visibility)
 * - Captures Web Vitals and performance metrics
 * - Stores everything in chrome.storage.local
 * - Communicates with popup via chrome.runtime.onMessage
 */
(function () {
    'use strict';

    let isRecording = false;
    let sessionData = {
        clicks: [],
        networkCalls: [],
        consoleLogs: [],
        errors: [],
        navigations: [],
        scrollEvents: [],
        keyboardEvents: [],
        formSubmissions: [],
        visibilityChanges: [],
        webVitals: {},
        longTasks: [],
        memorySnapshots: [],
        performanceTimings: [],
        metadata: {
            startTime: null,
            stopTime: null,
            startUrl: null,
            userAgent: navigator.userAgent,
            screenWidth: screen.width,
            screenHeight: screen.height,
            devicePixelRatio: window.devicePixelRatio,
            language: navigator.language
        }
    };

    // ──────────────────────────────────────────────
    //  STORAGE PERSISTENCE (batched, not per-event)
    // ──────────────────────────────────────────────

    let saveTimer = null;
    function scheduleSave() {
        if (saveTimer) return;
        saveTimer = setTimeout(function () {
            saveTimer = null;
            chrome.storage.local.set({
                isRecording: isRecording,
                sessionData: sessionData
            });
        }, 1000);
    }

    // ──────────────────────────────────────────────
    //  RESUME STATE ON PAGE RELOAD
    // ──────────────────────────────────────────────

    function loadFromStorage() {
        chrome.storage.local.get(['isRecording', 'sessionData'], function (result) {
            if (result.isRecording) {
                isRecording = true;
                if (result.sessionData) {
                    sessionData = result.sessionData;
                }
                // Tell MAIN world interceptor to start
                window.postMessage({ source: 'TRACECRAFT_CONTROL', action: 'start' }, '*');
                setupPerformanceObservers();
            }
        });
    }

    loadFromStorage();

    // ──────────────────────────────────────────────
    //  RECEIVE DATA FROM MAIN WORLD (page-interceptor.js)
    // ──────────────────────────────────────────────

    window.addEventListener('message', function (event) {
        if (event.source !== window) return;
        if (!event.data || event.data.source !== 'TRACECRAFT_INTERCEPTOR') return;
        if (!isRecording) return;

        var type = event.data.type;
        var payload = event.data.payload;

        switch (type) {
            case 'NETWORK':
                sessionData.networkCalls.push(payload);
                break;
            case 'CONSOLE':
                sessionData.consoleLogs.push(payload);
                break;
            case 'ERROR':
                sessionData.errors.push(payload);
                break;
            case 'NAVIGATION':
                sessionData.navigations.push(payload);
                break;
            case 'MEMORY':
                sessionData.memorySnapshots.push(payload);
                break;
            case 'INTERCEPTOR_READY':
                // Page-interceptor loaded (or reloaded). If we're recording, tell it to start.
                if (isRecording) {
                    window.postMessage({ source: 'TRACECRAFT_CONTROL', action: 'start' }, '*');
                }
                break;
            default:
                return; // unknown type, don't save
        }
        scheduleSave();
    });

    // ──────────────────────────────────────────────
    //  CLICK CAPTURE
    // ──────────────────────────────────────────────

    document.addEventListener('click', function (e) {
        if (!isRecording) return;
        var target = e.target;
        var rect = target.getBoundingClientRect();

        sessionData.clicks.push({
            timestamp: Date.now(),
            x: e.clientX,
            y: e.clientY,
            pageX: e.pageX,
            pageY: e.pageY,
            target: {
                tag: target.tagName,
                id: target.id || null,
                classes: target.className ? String(target.className).split(/\s+/).filter(Boolean) : [],
                name: target.getAttribute('name') || null,
                type: target.getAttribute('type') || null,
                href: target.href || null,
                text: (target.innerText || '').substring(0, 100),
                ariaLabel: target.getAttribute('aria-label') || null,
                role: target.getAttribute('role') || null,
                cssSelector: getCSSSelector(target),
                rect: {
                    top: Math.round(rect.top),
                    left: Math.round(rect.left),
                    width: Math.round(rect.width),
                    height: Math.round(rect.height)
                }
            },
            pageUrl: location.href,
            button: e.button,
            modifiers: { alt: e.altKey, ctrl: e.ctrlKey, shift: e.shiftKey, meta: e.metaKey }
        });
        scheduleSave();
    }, true);

    // ──────────────────────────────────────────────
    //  KEYBOARD CAPTURE (privacy-aware)
    // ──────────────────────────────────────────────

    document.addEventListener('keydown', function (e) {
        if (!isRecording) return;
        var target = e.target;
        var inputType = (target.getAttribute('type') || '').toLowerCase();
        var isSensitive = inputType === 'password' ||
            (target.autocomplete && /password|cc-|credit/i.test(target.autocomplete)) ||
            target.getAttribute('data-sensitive') !== null;

        sessionData.keyboardEvents.push({
            timestamp: Date.now(),
            key: isSensitive ? '[REDACTED]' : e.key,
            code: e.code,
            targetTag: target.tagName,
            targetId: target.id || null,
            inputType: inputType || null,
            isSensitive: isSensitive,
            pageUrl: location.href
        });
        scheduleSave();
    }, true);

    // ──────────────────────────────────────────────
    //  SCROLL CAPTURE (throttled)
    // ──────────────────────────────────────────────

    var lastScrollTime = 0;
    var SCROLL_THROTTLE = 250;

    document.addEventListener('scroll', function () {
        if (!isRecording) return;
        var now = Date.now();
        if (now - lastScrollTime < SCROLL_THROTTLE) return;
        lastScrollTime = now;

        sessionData.scrollEvents.push({
            timestamp: now,
            scrollX: Math.round(window.scrollX),
            scrollY: Math.round(window.scrollY),
            pageHeight: document.documentElement.scrollHeight,
            viewportHeight: window.innerHeight,
            pageUrl: location.href
        });
        scheduleSave();
    }, { passive: true, capture: true });

    // ──────────────────────────────────────────────
    //  FORM SUBMISSION CAPTURE
    // ──────────────────────────────────────────────

    document.addEventListener('submit', function (e) {
        if (!isRecording) return;
        var form = e.target;
        var formData;
        try { formData = new FormData(form); } catch (_) { formData = null; }

        var fields = {};
        var sensitivePattern = /password|passwd|secret|token|cvv|ssn|credit|card/i;

        if (formData) {
            formData.forEach(function (value, key) {
                fields[key] = sensitivePattern.test(key) ? '[REDACTED]' : String(value).substring(0, 500);
            });
        }

        sessionData.formSubmissions.push({
            timestamp: Date.now(),
            action: form.action || location.href,
            method: (form.method || 'GET').toUpperCase(),
            fieldCount: Object.keys(fields).length,
            fields: fields,
            pageUrl: location.href
        });
        scheduleSave();
    }, true);

    // ──────────────────────────────────────────────
    //  PAGE VISIBILITY CHANGES
    // ──────────────────────────────────────────────

    document.addEventListener('visibilitychange', function () {
        if (!isRecording) return;
        sessionData.visibilityChanges.push({
            timestamp: Date.now(),
            state: document.visibilityState,
            pageUrl: location.href
        });
        scheduleSave();
    });

    // ──────────────────────────────────────────────
    //  CSP VIOLATION CAPTURE
    // ──────────────────────────────────────────────

    document.addEventListener('securitypolicyviolation', function (e) {
        if (!isRecording) return;
        sessionData.errors.push({
            timestamp: Date.now(),
            type: 'csp-violation',
            message: 'CSP Violation: ' + e.violatedDirective,
            blockedURI: e.blockedURI,
            violatedDirective: e.violatedDirective,
            effectiveDirective: e.effectiveDirective,
            pageUrl: location.href
        });
        scheduleSave();
    });

    // ──────────────────────────────────────────────
    //  PERFORMANCE OBSERVERS (Web Vitals + Long Tasks)
    // ──────────────────────────────────────────────

    function setupPerformanceObservers() {
        // LCP
        try {
            new PerformanceObserver(function (list) {
                var entries = list.getEntries();
                var last = entries[entries.length - 1];
                sessionData.webVitals.lcp = {
                    value: Math.round(last.startTime),
                    element: last.element ? last.element.tagName : null,
                    url: last.url || null,
                    size: last.size,
                    timestamp: Date.now()
                };
                scheduleSave();
            }).observe({ type: 'largest-contentful-paint', buffered: true });
        } catch (_) { /* not supported */ }

        // CLS
        var clsValue = 0;
        try {
            new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) {
                    if (!entry.hadRecentInput) {
                        clsValue += entry.value;
                        sessionData.webVitals.cls = {
                            value: Math.round(clsValue * 1000) / 1000,
                            timestamp: Date.now()
                        };
                    }
                });
                scheduleSave();
            }).observe({ type: 'layout-shift', buffered: true });
        } catch (_) { /* not supported */ }

        // Long Tasks
        try {
            new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) {
                    sessionData.longTasks.push({
                        timestamp: Date.now(),
                        startTime: Math.round(entry.startTime),
                        duration: Math.round(entry.duration),
                        blockingTime: Math.round(entry.duration - 50)
                    });
                });
                scheduleSave();
            }).observe({ type: 'longtask', buffered: true });
        } catch (_) { /* not supported */ }

        // Resource timings (for performance timing data)
        try {
            new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) {
                    if (entry.initiatorType === 'fetch' || entry.initiatorType === 'xmlhttprequest') {
                        sessionData.performanceTimings.push({
                            timestamp: Date.now(),
                            url: entry.name,
                            initiatorType: entry.initiatorType,
                            dnsLookup: Math.round(entry.domainLookupEnd - entry.domainLookupStart),
                            tcpConnect: Math.round(entry.connectEnd - entry.connectStart),
                            tlsHandshake: entry.secureConnectionStart > 0 ?
                                Math.round(entry.connectEnd - entry.secureConnectionStart) : 0,
                            ttfb: Math.round(entry.responseStart - entry.requestStart),
                            downloadTime: Math.round(entry.responseEnd - entry.responseStart),
                            totalDuration: Math.round(entry.duration),
                            transferSize: entry.transferSize,
                            encodedBodySize: entry.encodedBodySize,
                            decodedBodySize: entry.decodedBodySize,
                            nextHopProtocol: entry.nextHopProtocol || null
                        });
                    }
                });
                scheduleSave();
            }).observe({ type: 'resource', buffered: true });
        } catch (_) { /* not supported */ }

        // Navigation timing (TTFB for the page itself)
        try {
            var navEntry = performance.getEntriesByType('navigation')[0];
            if (navEntry) {
                sessionData.webVitals.ttfb = {
                    value: Math.round(navEntry.responseStart),
                    timestamp: Date.now()
                };
                sessionData.webVitals.domInteractive = {
                    value: Math.round(navEntry.domInteractive),
                    timestamp: Date.now()
                };
                sessionData.webVitals.domComplete = {
                    value: Math.round(navEntry.domComplete),
                    timestamp: Date.now()
                };
                scheduleSave();
            }
        } catch (_) { /* not supported */ }

        // FID / INP via event timing
        try {
            var interactionLatencies = [];
            new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) {
                    if (entry.interactionId && entry.duration > 0) {
                        interactionLatencies.push(entry.duration);
                        var sorted = interactionLatencies.slice().sort(function (a, b) { return a - b; });
                        var p98idx = Math.floor(sorted.length * 0.98);
                        sessionData.webVitals.inp = {
                            value: sorted[p98idx],
                            sampleCount: sorted.length,
                            timestamp: Date.now()
                        };
                    }
                });
                scheduleSave();
            }).observe({ type: 'event', buffered: true, durationThreshold: 16 });
        } catch (_) { /* not supported */ }
    }

    // ──────────────────────────────────────────────
    //  CSS SELECTOR UTILITY
    // ──────────────────────────────────────────────

    function getCSSSelector(el) {
        if (!el || el.nodeType !== 1) return '';
        if (el.id) return '#' + el.id;
        var parts = [];
        while (el && el.nodeType === 1) {
            var part = el.tagName.toLowerCase();
            if (el.id) {
                parts.unshift('#' + el.id);
                break;
            }
            var parent = el.parentElement;
            if (parent) {
                var siblings = Array.from(parent.children).filter(function (s) {
                    return s.tagName === el.tagName;
                });
                if (siblings.length > 1) {
                    part += ':nth-of-type(' + (siblings.indexOf(el) + 1) + ')';
                }
            }
            parts.unshift(part);
            el = parent;
        }
        return parts.join(' > ');
    }

    // ──────────────────────────────────────────────
    //  MESSAGE HANDLING (popup <-> content script)
    // ──────────────────────────────────────────────

    chrome.runtime.onMessage.addListener(function (message, sender, sendResponse) {
        if (message.action === 'startRecording') {
            isRecording = true;
            sessionData = {
                clicks: [],
                networkCalls: [],
                consoleLogs: [],
                errors: [],
                navigations: [],
                scrollEvents: [],
                keyboardEvents: [],
                formSubmissions: [],
                visibilityChanges: [],
                webVitals: {},
                longTasks: [],
                memorySnapshots: [],
                performanceTimings: [],
                metadata: {
                    startTime: Date.now(),
                    stopTime: null,
                    startUrl: location.href,
                    userAgent: navigator.userAgent,
                    screenWidth: screen.width,
                    screenHeight: screen.height,
                    devicePixelRatio: window.devicePixelRatio,
                    language: navigator.language
                }
            };

            // Tell MAIN world interceptor to start
            window.postMessage({ source: 'TRACECRAFT_CONTROL', action: 'start' }, '*');

            chrome.storage.local.set({ isRecording: true, sessionData: sessionData });
            setupPerformanceObservers();
            sendResponse({ success: true });
        }
        else if (message.action === 'stopRecording') {
            isRecording = false;
            sessionData.metadata.stopTime = Date.now();

            // Tell MAIN world interceptor to stop
            window.postMessage({ source: 'TRACECRAFT_CONTROL', action: 'stop' }, '*');

            chrome.storage.local.set({ isRecording: false, sessionData: sessionData });
            sendResponse({ success: true, data: sessionData });
        }
        else if (message.action === 'getState') {
            sendResponse({
                isRecording: isRecording,
                stats: {
                    clicks: sessionData.clicks.length,
                    networkCalls: sessionData.networkCalls.length,
                    consoleLogs: sessionData.consoleLogs.length,
                    errors: sessionData.errors.length,
                    navigations: sessionData.navigations.length,
                    scrollEvents: sessionData.scrollEvents.length,
                    keyboardEvents: sessionData.keyboardEvents.length,
                    formSubmissions: sessionData.formSubmissions.length,
                    longTasks: sessionData.longTasks.length,
                    memorySnapshots: sessionData.memorySnapshots.length,
                    performanceTimings: sessionData.performanceTimings.length
                },
                webVitals: sessionData.webVitals
            });
        }
        return true; // keep message channel open for async sendResponse
    });
})();

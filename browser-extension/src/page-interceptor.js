/**
 * TraceCraft Genesis — MAIN world page interceptor
 *
 * This script runs in the page's JavaScript context (world: "MAIN")
 * at document_start, BEFORE any page scripts execute.
 *
 * It intercepts: fetch, XMLHttpRequest, console methods, errors,
 * unhandled promise rejections, and SPA navigation.
 *
 * Data is sent to the isolated-world content script via window.postMessage.
 */
(function () {
    'use strict';

    let intercepting = false;
    let idCounter = 0;

    function generateId() {
        return 'tc_' + Date.now() + '_' + (++idCounter);
    }

    function post(type, payload) {
        try {
            window.postMessage({ source: 'TRACECRAFT_INTERCEPTOR', type, payload }, '*');
        } catch (_) { /* ignore serialization errors */ }
    }

    function safeStringify(obj, maxLen) {
        if (obj === null || obj === undefined) return null;
        if (typeof obj !== 'object') return String(obj).substring(0, maxLen || 10000);
        try {
            const s = JSON.stringify(obj);
            return s.length > (maxLen || 100000) ? s.substring(0, maxLen || 100000) + '...[truncated]' : s;
        } catch (_) { return String(obj); }
    }

    // ──────────────────────────────────────────────
    //  FETCH INTERCEPTION
    // ──────────────────────────────────────────────

    const originalFetch = window.fetch.bind(window);

    window.fetch = async function (input, init) {
        if (!intercepting) return originalFetch(input, init);

        const id = generateId();
        const startTime = performance.now();
        const startEpoch = Date.now();

        // Extract request details
        const isRequest = input instanceof Request;
        const url = isRequest ? input.url : String(input);
        const method = (init && init.method) || (isRequest ? input.method : 'GET');

        // Request headers
        const reqHeaders = {};
        const headerSource = (init && init.headers) || (isRequest ? input.headers : null);
        if (headerSource) {
            if (headerSource instanceof Headers) {
                headerSource.forEach(function (v, k) { reqHeaders[k] = v; });
            } else if (typeof headerSource === 'object') {
                Object.keys(headerSource).forEach(function (k) { reqHeaders[k] = headerSource[k]; });
            }
        }

        // Request body
        let reqBody = null;
        const bodySource = (init && init.body) || (isRequest && !input.bodyUsed ? null : null);
        if (bodySource) {
            reqBody = extractBody(bodySource);
        } else if (isRequest && !input.bodyUsed) {
            try {
                const cloned = input.clone();
                reqBody = await cloned.text();
                if (reqBody.length > 100000) reqBody = reqBody.substring(0, 100000) + '...[truncated]';
            } catch (_) { reqBody = null; }
        }

        // Parse query params
        let queryParams = {};
        try {
            const urlObj = new URL(url, location.href);
            urlObj.searchParams.forEach(function (v, k) { queryParams[k] = v; });
        } catch (_) { /* invalid URL */ }

        try {
            const response = await originalFetch(input, init);
            const duration = performance.now() - startTime;
            const clonedResponse = response.clone();

            // Response headers
            const respHeaders = {};
            clonedResponse.headers.forEach(function (v, k) { respHeaders[k] = v; });

            // Read response body asynchronously
            readResponseBody(clonedResponse).then(function (respBody) {
                post('NETWORK', {
                    id: id,
                    timestamp: startEpoch,
                    type: 'fetch',
                    method: method,
                    url: url,
                    queryParams: queryParams,
                    requestHeaders: redactHeaders(reqHeaders),
                    requestBody: reqBody,
                    status: response.status,
                    statusText: response.statusText,
                    responseHeaders: respHeaders,
                    responseBody: respBody,
                    responseSize: parseInt(respHeaders['content-length'] || '0') || 0,
                    duration: Math.round(duration),
                    pageUrl: location.href,
                    initiatorType: 'fetch'
                });
            });

            return response;
        } catch (err) {
            const duration = performance.now() - startTime;
            post('NETWORK', {
                id: id,
                timestamp: startEpoch,
                type: 'fetch',
                method: method,
                url: url,
                queryParams: queryParams,
                requestHeaders: redactHeaders(reqHeaders),
                requestBody: reqBody,
                status: 0,
                statusText: 'Network Error',
                responseHeaders: {},
                responseBody: null,
                responseSize: 0,
                duration: Math.round(duration),
                error: err.message,
                pageUrl: location.href,
                initiatorType: 'fetch'
            });
            throw err;
        }
    };

    // ──────────────────────────────────────────────
    //  XMLHttpRequest INTERCEPTION
    // ──────────────────────────────────────────────

    const OrigXHR = window.XMLHttpRequest;
    const origOpen = OrigXHR.prototype.open;
    const origSend = OrigXHR.prototype.send;
    const origSetRequestHeader = OrigXHR.prototype.setRequestHeader;

    OrigXHR.prototype.open = function (method, url) {
        this._tc_id = generateId();
        this._tc_method = method;
        this._tc_url = url;
        this._tc_reqHeaders = {};
        origOpen.apply(this, arguments);
    };

    OrigXHR.prototype.setRequestHeader = function (name, value) {
        if (this._tc_reqHeaders) {
            this._tc_reqHeaders[name] = value;
        }
        origSetRequestHeader.apply(this, arguments);
    };

    OrigXHR.prototype.send = function (body) {
        if (!intercepting) {
            origSend.apply(this, arguments);
            return;
        }

        this._tc_reqBody = extractBody(body);
        this._tc_startTime = performance.now();
        this._tc_startEpoch = Date.now();

        var self = this;

        function onDone() {
            var duration = performance.now() - self._tc_startTime;

            // Parse response headers
            var respHeaders = {};
            var raw = self.getAllResponseHeaders() || '';
            raw.split('\r\n').forEach(function (line) {
                var idx = line.indexOf(': ');
                if (idx > 0) {
                    respHeaders[line.substring(0, idx).toLowerCase()] = line.substring(idx + 2);
                }
            });

            // Read response body
            var responseBody = null;
            if (self.responseType === '' || self.responseType === 'text') {
                responseBody = self.responseText;
                if (responseBody && responseBody.length > 100000) {
                    responseBody = responseBody.substring(0, 100000) + '...[truncated]';
                }
            } else if (self.responseType === 'json') {
                responseBody = safeStringify(self.response, 100000);
            } else if (self.responseType === 'arraybuffer' && self.response) {
                responseBody = '[ArrayBuffer: ' + self.response.byteLength + ' bytes]';
            } else if (self.responseType === 'blob' && self.response) {
                responseBody = '[Blob: ' + self.response.size + ' bytes]';
            }

            post('NETWORK', {
                id: self._tc_id,
                timestamp: self._tc_startEpoch,
                type: 'xhr',
                method: self._tc_method,
                url: self._tc_url,
                queryParams: parseQueryParams(self._tc_url),
                requestHeaders: redactHeaders(self._tc_reqHeaders || {}),
                requestBody: self._tc_reqBody,
                status: self.status,
                statusText: self.statusText,
                responseHeaders: respHeaders,
                responseBody: responseBody,
                responseSize: parseInt(respHeaders['content-length'] || '0') ||
                    (responseBody ? responseBody.length : 0),
                duration: Math.round(duration),
                pageUrl: location.href,
                initiatorType: 'xhr'
            });
        }

        this.addEventListener('load', onDone, { once: true });
        this.addEventListener('error', function () {
            var duration = performance.now() - self._tc_startTime;
            post('NETWORK', {
                id: self._tc_id,
                timestamp: self._tc_startEpoch,
                type: 'xhr',
                method: self._tc_method,
                url: self._tc_url,
                status: 0,
                error: 'Network error',
                duration: Math.round(duration),
                pageUrl: location.href,
                initiatorType: 'xhr'
            });
        }, { once: true });
        this.addEventListener('abort', function () {
            post('NETWORK', {
                id: self._tc_id,
                timestamp: self._tc_startEpoch,
                type: 'xhr',
                method: self._tc_method,
                url: self._tc_url,
                status: 0,
                error: 'Aborted',
                duration: Math.round(performance.now() - self._tc_startTime),
                pageUrl: location.href,
                initiatorType: 'xhr'
            });
        }, { once: true });

        origSend.apply(this, arguments);
    };

    // ──────────────────────────────────────────────
    //  CONSOLE INTERCEPTION
    // ──────────────────────────────────────────────

    var originalConsole = {};
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
        originalConsole[level] = console[level].bind(console);
        console[level] = function () {
            var args = Array.prototype.slice.call(arguments);
            if (intercepting) {
                try {
                    post('CONSOLE', {
                        timestamp: Date.now(),
                        level: level,
                        message: args.map(function (a) {
                            if (a === null) return 'null';
                            if (a === undefined) return 'undefined';
                            if (typeof a !== 'object') return String(a);
                            try { return JSON.stringify(a); } catch (_) { return String(a); }
                        }).join(' '),
                        pageUrl: location.href
                    });
                } catch (_) { /* ignore */ }
            }
            originalConsole[level].apply(console, args);
        };
    });

    // ──────────────────────────────────────────────
    //  ERROR CAPTURE
    // ──────────────────────────────────────────────

    window.addEventListener('error', function (e) {
        if (!intercepting) return;
        post('ERROR', {
            timestamp: Date.now(),
            type: 'uncaught',
            message: e.message,
            filename: e.filename,
            lineno: e.lineno,
            colno: e.colno,
            stack: e.error ? e.error.stack : null,
            pageUrl: location.href
        });
    }, true);

    window.addEventListener('unhandledrejection', function (e) {
        if (!intercepting) return;
        var reason = e.reason;
        post('ERROR', {
            timestamp: Date.now(),
            type: 'unhandledrejection',
            message: reason instanceof Error ? reason.message : String(reason),
            stack: reason instanceof Error ? reason.stack : null,
            pageUrl: location.href
        });
    });

    // ──────────────────────────────────────────────
    //  SPA NAVIGATION INTERCEPTION
    // ──────────────────────────────────────────────

    var origPushState = history.pushState.bind(history);
    var origReplaceState = history.replaceState.bind(history);

    history.pushState = function (state, title, url) {
        origPushState(state, title, url);
        if (intercepting) {
            post('NAVIGATION', {
                timestamp: Date.now(),
                type: 'pushState',
                url: url ? String(url) : location.href,
                pageUrl: location.href
            });
        }
    };

    history.replaceState = function (state, title, url) {
        origReplaceState(state, title, url);
        if (intercepting) {
            post('NAVIGATION', {
                timestamp: Date.now(),
                type: 'replaceState',
                url: url ? String(url) : location.href,
                pageUrl: location.href
            });
        }
    };

    window.addEventListener('popstate', function () {
        if (!intercepting) return;
        post('NAVIGATION', {
            timestamp: Date.now(),
            type: 'popstate',
            url: location.href,
            pageUrl: location.href
        });
    });

    // ──────────────────────────────────────────────
    //  MEMORY SNAPSHOTS (every 10s while recording)
    // ──────────────────────────────────────────────

    setInterval(function () {
        if (intercepting && performance.memory) {
            post('MEMORY', {
                timestamp: Date.now(),
                usedJSHeapSize: performance.memory.usedJSHeapSize,
                totalJSHeapSize: performance.memory.totalJSHeapSize,
                jsHeapSizeLimit: performance.memory.jsHeapSizeLimit
            });
        }
    }, 10000);

    // ──────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────

    function extractBody(body) {
        if (!body) return null;
        if (typeof body === 'string') {
            return body.length > 100000 ? body.substring(0, 100000) + '...[truncated]' : body;
        }
        if (body instanceof URLSearchParams) return body.toString();
        if (body instanceof FormData) {
            var parts = [];
            body.forEach(function (v, k) {
                if (v instanceof File) {
                    parts.push(k + '=[File: ' + v.name + ', ' + v.size + ' bytes]');
                } else {
                    parts.push(k + '=' + String(v).substring(0, 500));
                }
            });
            return parts.join('&');
        }
        if (body instanceof Blob) return '[Blob: ' + body.size + ' bytes, type=' + body.type + ']';
        if (body instanceof ArrayBuffer) return '[ArrayBuffer: ' + body.byteLength + ' bytes]';
        return String(body).substring(0, 10000);
    }

    async function readResponseBody(response) {
        var ct = response.headers.get('content-type') || '';
        try {
            if (ct.indexOf('application/json') !== -1 || ct.indexOf('text/') !== -1 ||
                ct.indexOf('javascript') !== -1 || ct.indexOf('xml') !== -1) {
                var text = await response.text();
                return text.length > 100000 ? text.substring(0, 100000) + '...[truncated]' : text;
            }
            var blob = await response.blob();
            return '[Binary: ' + blob.size + ' bytes, type=' + blob.type + ']';
        } catch (_) { return null; }
    }

    function redactHeaders(headers) {
        var redacted = {};
        var sensitiveKeys = /^(authorization|cookie|set-cookie|x-api-key|x-auth-token)$/i;
        Object.keys(headers).forEach(function (k) {
            if (sensitiveKeys.test(k)) {
                var val = headers[k];
                redacted[k] = val ? val.substring(0, 10) + '...[REDACTED]' : '[REDACTED]';
            } else {
                redacted[k] = headers[k];
            }
        });
        return redacted;
    }

    function parseQueryParams(url) {
        try {
            var urlObj = new URL(url, location.href);
            var params = {};
            urlObj.searchParams.forEach(function (v, k) { params[k] = v; });
            return params;
        } catch (_) { return {}; }
    }

    // ──────────────────────────────────────────────
    //  START / STOP CONTROL (via postMessage from isolated world)
    // ──────────────────────────────────────────────

    window.addEventListener('message', function (event) {
        if (event.source !== window) return;
        if (!event.data || event.data.source !== 'TRACECRAFT_CONTROL') return;

        if (event.data.action === 'start') {
            intercepting = true;
            idCounter = 0;
        } else if (event.data.action === 'stop') {
            intercepting = false;
        }
    });

    // Check if we should resume recording (page reload during recording)
    post('INTERCEPTOR_READY', { timestamp: Date.now() });
})();

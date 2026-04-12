/**
 * TraceCraft Genesis — Background service worker
 *
 * Captures additional network metadata via chrome.webRequest that
 * content scripts cannot access (e.g., request type, from-cache,
 * server IP, resource type for images/scripts/css).
 */

const requestMetadata = new Map();

// Capture request start info
chrome.webRequest.onBeforeRequest.addListener(
    function (details) {
        requestMetadata.set(details.requestId, {
            requestId: details.requestId,
            url: details.url,
            method: details.method,
            type: details.type, // 'xmlhttprequest', 'script', 'image', 'stylesheet', etc.
            tabId: details.tabId,
            frameId: details.frameId,
            timestamp: details.timeStamp,
            requestBody: null
        });

        // Capture request body for POST/PUT/PATCH
        if (details.requestBody) {
            var entry = requestMetadata.get(details.requestId);
            if (details.requestBody.formData) {
                entry.requestBody = { type: 'formData', data: details.requestBody.formData };
            } else if (details.requestBody.raw && details.requestBody.raw.length > 0) {
                try {
                    var bytes = details.requestBody.raw[0].bytes;
                    if (bytes) {
                        entry.requestBody = {
                            type: 'raw',
                            data: new TextDecoder().decode(bytes).substring(0, 50000)
                        };
                    }
                } catch (_) { /* binary body, skip */ }
            }
        }
    },
    { urls: ['<all_urls>'] },
    ['requestBody']
);

// Capture response headers
chrome.webRequest.onHeadersReceived.addListener(
    function (details) {
        var entry = requestMetadata.get(details.requestId);
        if (entry) {
            entry.statusCode = details.statusCode;
            entry.responseHeaders = {};
            if (details.responseHeaders) {
                details.responseHeaders.forEach(function (h) {
                    entry.responseHeaders[h.name.toLowerCase()] = h.value;
                });
            }
        }
    },
    { urls: ['<all_urls>'] },
    ['responseHeaders']
);

// Capture completion info (from-cache, server IP)
chrome.webRequest.onCompleted.addListener(
    function (details) {
        var entry = requestMetadata.get(details.requestId);
        if (entry) {
            entry.fromCache = details.fromCache;
            entry.ip = details.ip || null;
            entry.completedAt = details.timeStamp;
            entry.duration = details.timeStamp - entry.timestamp;

            // Store resource-level metadata for the tab
            if (entry.tabId > 0) {
                chrome.storage.session.get(['resourceMetadata_' + entry.tabId], function (result) {
                    var existing = result['resourceMetadata_' + entry.tabId] || [];
                    // Keep last 500 entries to avoid memory issues
                    if (existing.length > 500) existing = existing.slice(-250);
                    existing.push({
                        url: entry.url,
                        method: entry.method,
                        type: entry.type,
                        statusCode: entry.statusCode,
                        fromCache: entry.fromCache,
                        ip: entry.ip,
                        duration: Math.round(entry.duration),
                        contentLength: entry.responseHeaders ? entry.responseHeaders['content-length'] : null,
                        contentType: entry.responseHeaders ? entry.responseHeaders['content-type'] : null
                    });
                    var data = {};
                    data['resourceMetadata_' + entry.tabId] = existing;
                    chrome.storage.session.set(data);
                });
            }

            requestMetadata.delete(details.requestId);
        }
    },
    { urls: ['<all_urls>'] }
);

// Clean up failed requests
chrome.webRequest.onErrorOccurred.addListener(
    function (details) {
        requestMetadata.delete(details.requestId);
    },
    { urls: ['<all_urls>'] }
);

// Clean up stale entries every 60 seconds
setInterval(function () {
    var cutoff = Date.now() - 60000;
    requestMetadata.forEach(function (entry, key) {
        if (entry.timestamp < cutoff) requestMetadata.delete(key);
    });
}, 60000);

chrome.runtime.onInstalled.addListener(function () {
    console.log('TraceCraft Genesis extension installed (v2.0.0)');
});

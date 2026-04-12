chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'recordingStats') {
        chrome.storage.local.set({ lastStats: message.data });
    }
    return true;
});

chrome.runtime.onInstalled.addListener(() => {
    console.log('Session Replay extension installed');
});

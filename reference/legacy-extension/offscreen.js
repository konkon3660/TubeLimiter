// Offscreen document cannot directly access chrome.storage
// Instead, we communicate with the background script via messages
async function getStorageFromBackground(keys) {
  try {
    const response = await chrome.runtime.sendMessage({
      action: 'getStorage',
      keys
    });
    return response?.data || {};
  } catch (e) {
    console.error('Failed to get storage from background:', e);
    return {};
  }
}

async function setStorageViaBackground(items) {
  try {
    await chrome.runtime.sendMessage({
      action: 'setStorage',
      items
    });
  } catch (e) {
    console.error('Failed to set storage via background:', e);
  }
}

// Sandbox iframe communication
let sandboxReady = false;
let messageCounter = 0;
const pendingMessages = new Map();

window.addEventListener('message', (event) => {
  const { action, messageId, success, result, error } = event.data;

  if (action === 'ready') {
    sandboxReady = true;
    console.log('Sandbox iframe is ready');
    return;
  }

  if (messageId && pendingMessages.has(messageId)) {
    const { resolve, reject } = pendingMessages.get(messageId);
    pendingMessages.delete(messageId);

    if (success) {
      resolve(result);
    } else {
      reject(new Error(error));
    }
  }
});

function sendToSandbox(action, data) {
  return new Promise((resolve, reject) => {
    const messageId = ++messageCounter;
    pendingMessages.set(messageId, { resolve, reject });

    const iframe = document.getElementById('sandbox');
    if (!iframe || !iframe.contentWindow) {
      reject(new Error('Sandbox iframe not available'));
      return;
    }

    iframe.contentWindow.postMessage({ action, data, messageId }, '*');

    // Timeout after 30 seconds
    setTimeout(() => {
      if (pendingMessages.has(messageId)) {
        pendingMessages.delete(messageId);
        reject(new Error('Sandbox message timeout'));
      }
    }, 30000);
  });
}

let modelLoaded = false;
let inferenceInProgress = false;

async function ensureModelLoaded() {
  if (modelLoaded) {
    console.log('[Image Recognition] Model already loaded');
    return;
  }

  console.log('[Image Recognition] Waiting for sandbox to be ready...');
  // Wait for sandbox to be ready
  let attempts = 0;
  while (!sandboxReady && attempts < 50) {
    await new Promise(r => setTimeout(r, 100));
    attempts++;
  }

  if (!sandboxReady) {
    throw new Error('Sandbox iframe not ready');
  }

  console.log('[Image Recognition] Sandbox ready, loading model...');
  const modelUrl = chrome.runtime.getURL('tfjs/tfjs_model/model.json');
  await sendToSandbox('loadModel', { modelUrl });
  modelLoaded = true;
  console.log('[Image Recognition] ✓ Model loaded in sandbox');
}

function loadImage(dataUrl) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = (e) => reject(e);
    img.src = dataUrl;
  });
}

async function inferProbStudyFromCapture(dataUrl) {
  console.log('[Image Recognition] Ensuring model is loaded...');
  await ensureModelLoaded();

  const { studyAiInputWidth, studyAiInputHeight } = await getStorageFromBackground([
    'studyAiInputWidth',
    'studyAiInputHeight'
  ]);

  const inputW = Number.isFinite(studyAiInputWidth) ? studyAiInputWidth : 224;
  const inputH = Number.isFinite(studyAiInputHeight) ? studyAiInputHeight : 224;
  console.log('[Image Recognition] Input dimensions:', inputW, 'x', inputH);

  console.log('[Image Recognition] Loading image from dataUrl...');
  const img = await loadImage(dataUrl);
  console.log('[Image Recognition] Image loaded, size:', img.width, 'x', img.height);

  console.log('[Image Recognition] Preprocessing image...');
  const canvas = new OffscreenCanvas(inputW, inputH);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(img, 0, 0, inputW, inputH);
  const imageData = ctx.getImageData(0, 0, inputW, inputH);
  console.log('[Image Recognition] Image preprocessed, data length:', imageData.data.length);

  // Send image data to sandbox for inference
  console.log('[Image Recognition] Sending image to sandbox for inference...');
  const probStudy = await sendToSandbox('infer', {
    imageData: Array.from(imageData.data),
    width: inputW,
    height: inputH
  });

  if (!Number.isFinite(probStudy)) {
    throw new Error('Model output is not a finite number.');
  }

  console.log('[Image Recognition] Raw inference output:', probStudy);
  return probStudy;
}

console.log('Offscreen document loaded');

// Handle messages from the background script
chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request?.action !== 'inferStudy') {
    sendResponse({ success: false, error: 'Invalid action' });
    return false;
  }

  // Handle async work
  (async () => {
    try {
      console.log('[Image Recognition] Received inferStudy message');

      if (!request.dataUrl) {
        console.error('[Image Recognition] No dataUrl provided');
        sendResponse({ success: false, error: 'No dataUrl provided' });
        return;
      }

      // Check if inference is already in progress
      if (inferenceInProgress) {
        console.log('[Image Recognition] ⊘ Inference already in progress, skipping this request');
        sendResponse({ success: true, skipped: true, reason: 'inference_in_progress' });
        return;
      }

      const { useStudyAI } = await getStorageFromBackground(['useStudyAI']);
      if (!useStudyAI) {
        console.log('[Image Recognition] Study AI is disabled, skipping');
        sendResponse({ success: true, skipped: true });
        return;
      }

      console.log('[Image Recognition] Received dataUrl, length:', request.dataUrl.length);

      try {
        inferenceInProgress = true;
        console.log('[Image Recognition] Starting AI inference...');
        const probStudy = await inferProbStudyFromCapture(request.dataUrl);
        console.log('[Image Recognition] ✓ Inference completed - probStudy:', probStudy.toFixed(4));
        console.log('[Image Recognition] Study probability:', (probStudy * 100).toFixed(2) + '%');
        await setStorageViaBackground({
          probStudy,
          probStudyUpdatedAt: Date.now(),
          probStudyError: null
        });
        console.log('[Image Recognition] ✓ Result saved to storage');
        sendResponse({ success: true });
      } catch (e) {
        console.error('[Image Recognition] ✗ Inference failed:', e);
        await setStorageViaBackground({
          probStudy: null,
          probStudyUpdatedAt: Date.now(),
          probStudyError: 'Inference failed: ' + (e?.message || 'Unknown error')
        });
        sendResponse({ success: false, error: e?.message || 'Unknown error' });
      } finally {
        inferenceInProgress = false;
        console.log('[Image Recognition] Inference lock released');
      }
    } catch (e) {
      inferenceInProgress = false;
      console.error('[Image Recognition] Error in message handler:', e);
      sendResponse({ success: false, error: e?.message || 'Unknown error' });
    }
  })();

  return true; // Keep the message channel open for the async response
});

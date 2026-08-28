// chrome.storage.local 래퍼. Infinity는 그대로 저장 안 되므로 마커로 변환.

const INFINITY_MARKER = '__INFINITY__';

export async function getStorage(keys) {
  return new Promise((resolve) => {
    chrome.storage.local.get(keys, (result) => {
      const processed = {};
      for (const key in result) {
        processed[key] = result[key] === INFINITY_MARKER ? Infinity : result[key];
      }
      resolve(processed);
    });
  });
}

export async function setStorage(items) {
  return new Promise((resolve) => {
    const processed = {};
    for (const key in items) {
      processed[key] = items[key] === Infinity ? INFINITY_MARKER : items[key];
    }
    chrome.storage.local.set(processed, () => resolve());
  });
}

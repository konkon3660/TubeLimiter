// utils/storage.js
// 스토리지 관련 유틸리티 함수

// Chrome storage는 Infinity를 null로 직렬화하므로 특별 처리 필요
const INFINITY_MARKER = '__INFINITY__';

export async function getStorage(keys) {
  return new Promise((resolve) => {
    chrome.storage.local.get(keys, (result) => {
      // Infinity 마커를 실제 Infinity로 변환
      const processedResult = {};
      for (const key in result) {
        if (result[key] === INFINITY_MARKER) {
          processedResult[key] = Infinity;
        } else {
          processedResult[key] = result[key];
        }
      }
      resolve(processedResult);
    });
  });
}

export async function setStorage(items) {
  return new Promise((resolve) => {
    // Infinity를 마커로 변환
    const processedItems = {};
    for (const key in items) {
      if (items[key] === Infinity) {
        processedItems[key] = INFINITY_MARKER;
      } else {
        processedItems[key] = items[key];
      }
    }
    chrome.storage.local.set(processedItems, () => {
      resolve();
    });
  });
}

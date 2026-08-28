function pauseAllVideos() {
  document.querySelectorAll('video').forEach((video) => {
    if (!video.paused) video.pause();
    video.muted = true;
  });
}

let blockWatchdog = null;

function applyBlockOverlay(reason) {
  if (document.getElementById('tube-limiter-overlay')) return;

  pauseAllVideos();

  // MutationObserver는 새 video 엘리먼트가 추가될 때만 잡는다.
  // 유튜브 자체 자동재생/버퍼링 재개는 기존 엘리먼트의 play()라 못 잡으므로 감시 필요.
  if (!blockWatchdog) blockWatchdog = setInterval(pauseAllVideos, 500);

  const overlay = document.createElement('div');
  overlay.id = 'tube-limiter-overlay';
  Object.assign(overlay.style, {
    position: 'fixed',
    top: '0',
    left: '0',
    width: '100%',
    height: '100%',
    backgroundColor: 'rgba(0, 0, 0, 1)',
    color: 'white',
    fontSize: '24px',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: '2147483647',
    textAlign: 'center',
    flexDirection: 'column'
  });

  const observer = new MutationObserver(pauseAllVideos);
  observer.observe(document.body, { childList: true, subtree: true });
  overlay.videoObserver = observer;

  document.body.appendChild(overlay);
  document.body.style.overflow = 'hidden';

  const messages = {
    focusMode: '집중 모드가 활성화되어 유튜브 시청이 제한되었습니다.',
    manualBlock: '유튜브가 수동으로 차단되었습니다.',
    alwaysBlockShorts: 'Shorts 항상 차단 설정으로 인해 시청이 제한되었습니다.',
    usageLimit: '일일 사용 시간 제한을 초과하여 유튜브 시청이 제한되었습니다.'
  };
  const message = messages[reason] || '유튜브 시청이 제한되었습니다.';

  overlay.innerHTML = `
    <h1>TubeLimiter</h1>
    <p>${message}</p>
    <p>오늘의 스트릭을 지켰는지는 내일 확인할 수 있어요.</p>
  `;
}

function removeBlockOverlay() {
  const overlay = document.getElementById('tube-limiter-overlay');
  if (!overlay) return;

  overlay.videoObserver?.disconnect();
  overlay.remove();
  if (blockWatchdog) {
    clearInterval(blockWatchdog);
    blockWatchdog = null;
  }
  document.querySelectorAll('video').forEach((video) => {
    video.muted = false;
  });
  document.body.style.overflow = '';
}

chrome.runtime.onMessage.addListener((request) => {
  if (request.action === 'blockYoutube') {
    applyBlockOverlay(request.reason);
  } else if (request.action === 'unblockYoutube') {
    removeBlockOverlay();
  }
});

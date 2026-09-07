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
    scheduledBlock: '예약된 차단 시간이라 유튜브 시청이 제한되었습니다.',
    manualBlock: '유튜브가 수동으로 차단되었습니다.',
    alwaysBlockShorts: 'Shorts 항상 차단 설정으로 인해 시청이 제한되었습니다.',
    // Shorts 한도는 전체 한도와 별개라, 문구도 "Shorts만 막혔다"는 걸 분명히 알려야 한다 —
    // 안 그러면 일반 영상은 멀쩡히 되는데 왜 여기만 막히는지 알 수 없다.
    shortsLimit: 'Shorts 일일 한도를 다 써서 Shorts만 제한되었습니다. 일반 영상은 남은 한도만큼 볼 수 있어요.',
    usageLimit: '일일 사용 시간 제한을 초과하여 유튜브 시청이 제한되었습니다.'
  };
  const message = messages[reason] || '유튜브 시청이 제한되었습니다.';

  overlay.innerHTML = `
    <h1>TubeLimiter</h1>
    <p>${message}</p>
    <p>오늘의 스트릭을 지켰는지는 내일 확인할 수 있어요.</p>
  `;

  // 오버레이가 영상을 멈춰 세웠다는 사실을 백그라운드에 바로 알린다. video.pause()가 내는
  // pause 이벤트는 오버레이가 DOM에 붙은 "뒤에" 도착하므로 그것만으로는 보고가 나가지 않는다.
  // 이게 없으면 백그라운드는 차단 직전의 "재생 중"을 계속 믿고 시간을 깎는다 — Shorts만 막히는
  // 차단(Shorts 항상 차단 · Shorts 한도)은 전역 집계 게이트(trackingBlocked)가 안 걸려서
  // 실제로 그 시간이 오늘 사용량에 쌓인다.
  reportPlaybackState();
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

// --- 알람 토스트: chrome.notifications는 OS가 위치/표시 여부를 정하다 보니
// 방해금지 모드 등에 묻히기 쉽다. 지금 보고 있는 화면에 직접, 눈에 띄게 띄워서 확실히 보이게 한다.
// 영상에 집중하고 있으면 작은 텍스트는 그냥 지나치기 쉬우므로 큼직하게, 튀는 색으로, 흔들리는
// 애니메이션과 함께 띄운다. 풀스크린 영상 재생 중엔 document.body에 붙여봐야 안 보이므로
// (풀스크린 요소가 다른 걸 전부 가림) fullscreenElement가 있으면 그 안에 붙인다.

let alarmToastTimer = null;
let alarmToastStyleInjected = false;

function ensureAlarmToastStyle() {
  if (alarmToastStyleInjected) return;
  alarmToastStyleInjected = true;
  const style = document.createElement('style');
  style.textContent = `
    @keyframes tube-limiter-toast-in {
      0% { transform: translateX(-40px) scale(0.9); opacity: 0; }
      60% { transform: translateX(4px) scale(1.02); opacity: 1; }
      100% { transform: translateX(0) scale(1); opacity: 1; }
    }
    @keyframes tube-limiter-toast-pulse {
      0%, 100% { box-shadow: 0 6px 24px rgba(0,0,0,0.45), 0 0 0 0 rgba(255, 87, 34, 0.6); }
      50% { box-shadow: 0 6px 24px rgba(0,0,0,0.45), 0 0 0 10px rgba(255, 87, 34, 0); }
    }
  `;
  document.head.appendChild(style);
}

function getAlarmToastContainer() {
  return document.fullscreenElement || document.body;
}

function showAlarmToast(message) {
  ensureAlarmToastStyle();

  let toast = document.getElementById('tube-limiter-alarm-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'tube-limiter-alarm-toast';
    Object.assign(toast.style, {
      position: 'fixed',
      top: '24px',
      left: '24px',
      zIndex: '2147483647',
      background: 'linear-gradient(135deg, #ff5722, #e64a19)',
      color: '#fff',
      padding: '18px 22px',
      borderRadius: '14px',
      fontSize: '19px',
      fontWeight: '700',
      lineHeight: '1.4',
      maxWidth: '360px',
      fontFamily: 'Roboto, Arial, sans-serif',
      cursor: 'pointer',
      animation: 'tube-limiter-toast-in 0.35s ease-out, tube-limiter-toast-pulse 1.2s ease-in-out 0.35s 2'
    });
    // 클릭하면 바로 닫히게. 8초 기다리기 싫을 때용.
    toast.addEventListener('click', () => {
      clearTimeout(alarmToastTimer);
      toast.remove();
    });
  }

  // 풀스크린 진입/해제로 컨테이너가 바뀌었으면 옮겨 붙인다.
  const container = getAlarmToastContainer();
  if (toast.parentElement !== container) container.appendChild(toast);

  toast.textContent = `⏰ ${message}`;
  toast.style.display = 'block';

  clearTimeout(alarmToastTimer);
  alarmToastTimer = setTimeout(() => {
    toast.remove();
  }, 8000);
}

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.action === 'blockYoutube') {
    applyBlockOverlay(request.reason);
  } else if (request.action === 'unblockYoutube') {
    removeBlockOverlay();
  } else if (request.action === 'showAlarmToast') {
    showAlarmToast(request.message);
  } else if (request.action === 'requestPlaybackState') {
    // 백그라운드가 재생 상태를 낙관적으로 가정해야 하는 자리(서비스워커 재시작, 탭 전환 등)에서
    // 추측 대신 지금 값을 직접 물어보는 경로. 조회가 동기라 바로 답한다.
    const playing = currentPlaybackState();
    lastReportedPlaying = playing;
    sendResponse({ playing });
  }
});

// --- 자동 소모 방지: 영상 재생 여부 + 탭 가시성을 백그라운드에 보고 ---
// 유튜브를 켜놓기만 하고 영상을 멈춘 채 방치("잠수")하면 시간이 깎이지 않도록,
// 실제로 영상이 재생 중이고 탭이 화면에 보일 때만 카운트한다.

let lastReportedPlaying = null;
let playbackWatcherId = null;

function isAnyVideoPlaying() {
  // 사이드바 추천 영상 썸네일에 마우스를 올리면 유튜브가 미리보기용 <video>를 별도로
  // 재생시킨다. 이를 구분 없이 잡으면 메인 영상은 일시정지해놓고 썸네일만 훑어봐도
  // "재생 중"으로 오판해 시청 시간이 깎인다. 메인 플레이어(.html5-main-video)만 본다.
  // 다만 유튜브 마크업이 바뀌어 못 찾을 수 있으니, 그럴 땐 전체 video를 훑는 기존 방식으로 대체.
  const mainVideos = document.querySelectorAll('video.html5-main-video');
  const videos = mainVideos.length > 0 ? mainVideos : document.querySelectorAll('video');
  for (const video of videos) {
    if (!video.paused && !video.ended && video.readyState > 2) return true;
  }
  return false;
}

function currentPlaybackState() {
  // 차단 오버레이가 떠 있으면 영상은 강제로 멈춰있는 상태라 재생 중일 수 없다.
  if (document.getElementById('tube-limiter-overlay')) return false;
  return document.visibilityState === 'visible' && isAnyVideoPlaying();
}

function reportPlaybackState() {
  // 오버레이가 떠 있으면 currentPlaybackState()가 false를 돌려준다. 예전엔 여기서 보고 자체를
  // 접었는데, 그러면 백그라운드가 차단 직전의 "재생 중"을 그대로 믿게 된다. 전역 차단
  // (집중 모드 · 전체 한도)은 trackingBlocked 게이트가 어차피 집계를 막아 티가 안 났지만,
  // Shorts만 막는 차단은 그 게이트가 안 걸리므로 멈춰 세운 영상 시간이 계속 쌓인다.
  // false를 한 번 보고하고 나면 lastReportedPlaying이 같아서 더 보내지도 않는다.
  const playing = currentPlaybackState();
  if (playing === lastReportedPlaying) return;
  try {
    // 확장 프로그램이 리로드/업데이트되면 이 탭의 content script는 남아있어도
    // chrome.runtime이 무효화되어 sendMessage가 "동기적으로" throw한다 (Promise reject가 아님).
    // .catch()로는 못 잡으므로 try/catch 필요. 이 경우 페이지 새로고침 전까진 복구 불가하니 감시 중단.
    chrome.runtime.sendMessage({ action: 'videoPlaybackState', playing })
      // 실제로 전달된 뒤에만 "보고했다"고 기억한다. 실패한 보고까지 기억해버리면 상태가
      // 다시 바뀌기 전까진 재전송을 안 해서, 백그라운드가 옛 값을 계속 믿게 된다
      // (일시정지해둔 영상이 계속 시청 중으로 집계되는 원인). 실패하면 다음 폴링이 재시도한다.
      .then(() => { lastReportedPlaying = playing; })
      .catch(() => {});
  } catch {
    if (playbackWatcherId) {
      clearInterval(playbackWatcherId);
      playbackWatcherId = null;
    }
  }
}

// play/pause는 버블링되지 않아 캡처 단계에서 위임 리스닝.
// 유튜브 SPA는 video 엘리먼트를 자주 교체하므로 폴링도 함께 돌린다.
document.addEventListener('play', reportPlaybackState, true);
document.addEventListener('pause', reportPlaybackState, true);
document.addEventListener('ended', reportPlaybackState, true);
document.addEventListener('visibilitychange', reportPlaybackState);
playbackWatcherId = setInterval(reportPlaybackState, 3000);
reportPlaybackState();

// content.js
// 유튜브 페이지에 삽입되는 스크립트 (콘텐츠 차단 등) 구현

// 비디오 일시정지 함수
function pauseAllVideos() {
  const videoElements = document.querySelectorAll('video');
  videoElements.forEach(video => {
    if (!video.paused) {
      video.pause();
      console.log('Video paused:', video); // Added log
    }
    video.muted = true;
  });
}

// 페이지 로드 시 유튜브 콘텐츠를 가리는 오버레이 생성
function applyBlockOverlay(reason) {
  const existingOverlay = document.getElementById('tube-limiter-overlay');
  if (existingOverlay) return; // 이미 오버레이가 있으면 다시 생성하지 않음

  console.log('Applying block overlay...'); // Added log

  // 즉시 비디오 일시정지
  pauseAllVideos();

  const overlay = document.createElement('div');
  overlay.id = 'tube-limiter-overlay';
  Object.assign(overlay.style, {
    position: 'fixed',
    top: '0',
    left: '0',
    width: '100%',
    height: '100%',
    backgroundColor: 'rgba(0, 0, 0, 1)', // 완전히 불투명한 검은색
    color: 'white',
    fontSize: '24px',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: '99999', // 다른 요소 위에 표시
    textAlign: 'center',
    flexDirection: 'column'
  });

  // 동적으로 추가되는 비디오도 일시정지하기 위한 MutationObserver
  const observer = new MutationObserver(() => {
    pauseAllVideos();
  });
  observer.observe(document.body, { childList: true, subtree: true });
  overlay.videoObserver = observer; // 나중에 정리하기 위해 저장

  document.body.appendChild(overlay);
  document.body.style.overflow = 'hidden'; // 스크롤 막기

  const isShortsPage = window.location.pathname.startsWith('/shorts');
  let message = '';
  if (reason === 'focusMode') {
    message = '집중 모드가 활성화되어 유튜브 시청이 제한되었습니다.';
  } else if (reason === 'manualBlock') {
    message = '유튜브가 수동으로 차단되었습니다.';
  } else if (reason === 'alwaysBlockShorts') {
    message = 'Shorts 항상 차단 설정으로 인해 시청이 제한되었습니다.';
  } else if (reason === 'usageLimit') {
    message = '일일 사용 시간 제한을 초과하여 유튜브 시청이 제한되었습니다.';
  } else {
    message = isShortsPage ? 'YouTube Shorts 시청이 제한되었습니다.' : '유튜브 시청이 제한되었습니다.';
  }

  overlay.innerHTML = `
    <h1>TubeLimiter</h1>
    <p>${message}</p>
    <p>건강한 디지털 습관을 위해 잠시 쉬어가세요.</p>
  `;
}

function removeBlockOverlay() {
  const existingOverlay = document.getElementById('tube-limiter-overlay');
  if (existingOverlay) {
    console.log('Removing block overlay...'); // Added log

    // MutationObserver 정리
    if (existingOverlay.videoObserver) {
      existingOverlay.videoObserver.disconnect();
      console.log('MutationObserver disconnected');
    }

    existingOverlay.remove();

    // 유튜브 비디오 음소거 해제 (재생은 사용자가 직접 하도록)
    const videoElements = document.querySelectorAll('video');
    videoElements.forEach(video => {
      video.muted = false;
      console.log('Video unmuted:', video);
    });
    document.body.style.overflow = ''; // 스크롤 허용
  }
}



// background script로부터 메시지 수신
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  console.log(`Content script received message: ${request.action}, reason: ${request.reason}`); // Added log
  if (request.action === "blockYoutube") {
    console.log(`Calling applyBlockOverlay with reason: ${request.reason}`);
    applyBlockOverlay(request.reason);
    console.log('TubeLimiter: Received block message from background.');
  } else if (request.action === "unblockYoutube") {
    console.log('Calling removeBlockOverlay.');
    removeBlockOverlay();
    console.log('TubeLimiter: Received unblock message from background.');
  }
});

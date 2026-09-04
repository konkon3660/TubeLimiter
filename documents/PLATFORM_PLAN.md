# 멀티플랫폼 확장 계획 (크롬 확장 / PC / 모바일)

이 문서는 "플랫폼별로 뭐가 필요한가"를 처음 정리한 기록. 모바일 스택/감지 방식 결정과 진행 상황은 이후 [MOBILE_PLAN.md](MOBILE_PLAN.md)로 넘어갔으므로 최신 상태는 그쪽 참고.

## 현황 정리

| 플랫폼 | 상태 | 비고 |
|---|---|---|
| 크롬 확장 (브라우저 유튜브) | 완료 | `extension/` |
| PC 유튜브 앱 (PWA) | **해결됨, 추가 작업 불필요** | 유튜브 PC 앱은 PWA라 크롬 확장이 그대로 작동함 |
| 모바일 앱 (Android 네이티브 유튜브 앱) | 진행 중 | `android/`. 네이티브 Kotlin으로 결정, 로컬 기능 대부분 완료 — 상세는 [MOBILE_PLAN.md](MOBILE_PLAN.md) |
| 모바일 앱 (iOS) | 보류 | 테스트 기기 없음 — 착수 안 함 |

## PC PWA가 되는 이유

PWA도 결국 같은 Chromium 엔진 위에서 돎. `manifest.json`의 `content_scripts.matches`가 `*://*.youtube.com/*`라서 설치된 PWA 앱 창에도 content script가 그대로 주입됨 → 차단 오버레이 정상 작동.

- 제약: PWA 앱 창은 툴바가 없어서 확장 아이콘/팝업(`popup.html`) 접근 불가. 설정은 일반 브라우저 탭에서 옵션 페이지(`options/options.html`)로 하면 됨.
- 차단 로직(content script) 자체는 영향 없음.

## 모바일 앱이 안 되는 이유

크롬 확장은 브라우저 API(`chrome.*`) 기반 — 네이티브 앱(유튜브 iOS/Android 앱)엔 확장 개념 자체가 없음. 별도 앱을 새로 만들어야 함.

- **Android**: Accessibility Service로 유튜브 앱 사용시간 감지 + 오버레이 차단. (또는 Digital Wellbeing 앱 타이머 활용도 검토 가능)
- **iOS**: Family Controls / DeviceActivity framework (Screen Time API) 사용.

## 코드 재사용성

이미 순수 함수로 분리되어 있어 재사용성 양호:

- `extension/src/lib/gamification.js` — chrome.* 의존 없음. supabase client도 인자로 받는 구조(DI) → 스트릭/XP/업적 로직 그대로 재사용 가능.
- `extension/src/lib/time.js` — 순수 함수, 의존성 없음.

재사용 **불가능**한 부분(플랫폼 전용이라 애초에 공유 대상 아님):
- `background/service-worker.js` (chrome.storage, chrome.alarms, chrome.tabs)
- `content/content.js` (DOM 접근, chrome.runtime 메시지 패싱)
- `popup/`, `options/` UI

공유 가능한 백엔드: Supabase (계정 인증, 사용량/스트릭/XP 저장) — 모바일 앱도 같은 프로젝트에 붙이면 크롬 확장과 계정/기록 공유됨.

## 스택 선택에 따른 재사용 범위

- **React Native였다면**: `lib/gamification.js`, `lib/time.js` 거의 그대로 재사용 가능했을 것.
- **실제 선택: 네이티브 Kotlin.** 이유와 근거는 [MOBILE_PLAN.md](MOBILE_PLAN.md) 참고(핵심 기능인 사용시간 감지가 어차피 네이티브 API라 RN 이점이 적다고 판단). JS 로직은 그대로 못 쓰고 Kotlin으로 재작성 — `lib/gamification.js`/`lib/time.js`의 판정 규칙(스트릭·XP·4시 컷오프)을 `android/.../gamification/`, `android/.../usage/DayWindow.kt` 등 순수 함수로 포팅함.
- 감지 방식도 Accessibility Service가 아니라 `UsageStatsManager`로 결정 — 배터리 부담이 적어서(자세한 비교는 MOBILE_PLAN.md).

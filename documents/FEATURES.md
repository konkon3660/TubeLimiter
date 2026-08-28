# TubeLimiter v2 — 만든 것 요약

## 뭐가 바뀌었나

기존(레거시)은 "시간 넘으면 차단"만 하는 회피형 도구였음. 새 버전은 차단 로직은 유지하면서 그 위에 **계정 동기화 + 게임화(스트릭/XP/업적)** 를 얹음. 목적: 차단이 뚫려도 "꾸준히 지킨 기록"이 아까워서 안 보게 만들기.

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 시청 제한 + 차단 | 일일/요일별 한도 설정, 넘으면 유튜브 페이지에 오버레이로 차단 |
| 집중 모드 | 정해진 시간 동안 한도와 무관하게 즉시 차단 |
| 긴급 시청 | 차단 상태에서 5분간 예외 허용, 횟수는 일/주/월 단위로 리셋 |
| 화이트리스트 / Shorts 차단 | 특정 URL 제외, Shorts 항상 차단 옵션 |
| 계정 로그인 | 이메일 회원가입/로그인 (Supabase Auth) |
| 클라우드 동기화 | 설정과 하루 사용량을 서버(Supabase)에 저장 → 여러 PC에서 같은 계정으로 로그인하면 공유됨 |
| 자동 스트릭 | 자정마다 그날 사용량이 한도 이내였는지 판정 → 성공하면 연속일수 +1, 실패하면 0으로 리셋 |
| XP / 레벨 | 성공 1일당 10XP, 3·7·14·30·60·100·365일 스트릭 달성 시 보너스 XP |
| 업적 뱃지 | 스트릭 마일스톤 달성 시 자동 잠금 해제 |
| 대시보드 | 최근 28일 성공/실패 히트맵, 업적 뱃지, 최근 14일 사용시간 막대그래프 |

수동 활동 기록(운동/공부 입력) 없음 — 전부 자동 판정. 기존에 있던 "화면 캡처해서 공부/놀기 AI로 구분" 기능은 이번 버전에서 드롭함(레거시에는 남아있음).

## 프로젝트 구조

```
TubeLimiter/
├── extension/              ← 새 크롬 확장 (여기가 실제 개발 대상)
│   ├── src/                ← 소스 (background, popup, options, dashboard, auth, lib)
│   ├── public/              ← manifest.json, html, 아이콘/css
│   ├── supabase/schema.sql  ← DB 테이블 + 보안 정책
│   └── README.md            ← 처음 설정 방법 (Supabase 가입부터 크롬 로드까지)
└── reference/legacy-extension/  ← 예전 대회 제출 버전 (참고용, 더 이상 수정 안 함)
```

## 아직 사람이 해야 할 것

1. supabase.com 가입 → 프로젝트 생성 → `schema.sql` 실행 → `extension/src/lib/config.js`에 URL/키 입력.
2. `cd extension && npm install && npm run build`
3. `chrome://extensions` → 개발자 모드 → `extension/dist` 폴더 로드해서 직접 테스트.

자세한 단계는 `extension/README.md` 참고.

## Manifest V3 참고 메모 (진행하며 챙긴 것들)

- **manifest_version 3 필수.** `permissions`(storage/tabs/scripting/alarms 등 크롬 API 권한)와 `host_permissions`(외부 URL 접근 권한, 여긴 `*://*.youtube.com/*`만 사용)는 분리해서 선언.
- **서비스워커는 계속 안 살아있음.** 일정 시간 idle이면 꺼짐 → 상태를 전역 변수에만 두면 날아감. 그래서 `chrome.storage.local`을 진짜 상태 저장소로 쓰고, `setInterval` 대신 `chrome.alarms`(1분 주기)로 주기 작업을 예약함 — 알람은 서비스워커가 꺼져 있어도 브라우저가 깨워서 실행해줌.
- **컨텍스트 분리:** content script(유튜브 페이지, DOM 접근 가능)와 background/popup/options(DOM 없음, chrome.* API 다 씀)는 서로 다른 실행 환경. 서로 대화하려면 메시지 패싱만 가능:
  - background/popup → content: `chrome.tabs.sendMessage(tabId, msg)`
  - content → background: `chrome.runtime.sendMessage(msg)`
  - 수신: `chrome.runtime.onMessage.addListener(...)`
- **CSP 제약:** html에 `<script>...</script>` 인라인 코드 금지, 항상 외부 .js 파일로 분리. `eval`이나 CDN에서 원격 코드 불러오기도 금지 — 그래서 Supabase 클라이언트도 CDN이 아니라 esbuild로 미리 번들링해서 로컬 파일로 포함시킴.

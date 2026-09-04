# TubeLimiter v2 — 만든 것 요약

## 뭐가 바뀌었나

기존(레거시)은 "시간 넘으면 차단"만 하는 회피형 도구였음. 새 버전은 차단 로직은 유지하면서 그 위에 **계정 동기화 + 게임화(스트릭/XP/업적)** 를 얹음. 목적: 차단이 뚫려도 "꾸준히 지킨 기록"이 아까워서 안 보게 만들기.

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 시청 제한 + 차단 | 일일/요일별 한도 설정, 넘으면 유튜브 페이지에 오버레이로 차단 |
| 집중 모드 | 정해진 시간 동안 한도와 무관하게 즉시 차단. 진짜 커밋먼트 장치가 되도록 활성 세션을 도중에 끄려면 하드코어 모드 해제처럼 10분 대기(요청 후 취소 가능)가 필요하고, 그동안에도 긴급 시청으로 우회할 수 없음. 단, 아직 시작 전인 "지연(분)" 예약을 취소하는 건 즉시 처리됨 — 시작한 적이 없으니 대기가 의미 없음 |
| 예약 차단 | 요일·시간대를 등록해두면 그 시간엔 한도와 무관하게 자동으로 차단(예: 매일 22:00~07:00). 집중 모드와 같은 급이라 긴급 시청으로 우회할 수 없고 화이트리스트도 통하지 않음. 옵션(안드로이드는 설정 화면)에서만 추가·수정·삭제할 수 있고, 팝업/홈 화면에는 활성 여부만 읽기 전용으로 표시 — 끄는 버튼을 따로 두지 않아 진짜 커밋먼트 장치로 남음. 4시 사용량 컷오프와 무관하게 실제 시계 기준으로 판정(상세는 [BACKEND.md](BACKEND.md)) |
| 긴급 시청 | 차단 상태에서 5분간 예외 허용(사용 한도·수동 차단에만 적용, 집중 모드·예약 차단은 우회 불가), 횟수는 일/주/월 단위로 리셋. 남용 방지로 한 번 쓰면 15초간 재요청 불가. 그동안 본 시간은 총 시청시간에 그대로 포함되지만, 스트릭 판정에서는 빼주고(스트릭은 안 끊김) 그날의 "완벽한 날" 자격만 잃는다 |
| 화이트리스트 / Shorts 차단 | 특정 URL 제외, Shorts 항상 차단 옵션 |
| 계정 로그인 | 이메일 회원가입/로그인 (Supabase Auth) |
| 클라우드 동기화 | 설정/스트릭/오늘 사용량을 서버(Supabase)에 저장 → 확장·안드로이드 여러 기기에서 같은 계정으로 로그인하면 공유됨. 오늘 사용량은 절대값 덮어쓰기가 아니라 델타 합산(`increment_daily_usage` RPC)이라 기기별 몫이 안 사라짐 — 상세는 [BACKEND.md](BACKEND.md) |
| 자동 스트릭 | 자정마다 그날 사용량이 한도 이내였는지 판정 → 성공하면 연속일수 +1, 실패하면 0으로 리셋. 판정에서 긴급 시청 시간은 빼고 보고(긴급 시청은 스트릭을 안 끊음), 한도를 넘는 순간이 든 마지막 정산 구간(최대 1분)은 측정 단위 때문에 생기는 초과라 봐준다 |
| 완벽한 날 | 긴급 시청을 한 번도 안 쓰고 한도를 지킨 날. 스트릭과 별도로 누적일수·연속기록을 세고(`perfect_days`, `current_perfect_streak`), 긴급 시청을 쓴 날엔 스트릭은 이어지지만 이쪽만 0으로 끊긴다 — 긴급 시청을 벌하지 않으면서도 "안 쓴 날"은 따로 보상하는 이원화 |
| XP / 레벨 | 성공 1일당 10XP, 완벽한 날이면 +10XP, 3·7·14·30·60·100·365일 스트릭 및 7·30·100일 완벽 연속 달성 시 보너스 XP |
| 업적 뱃지 | 스트릭 마일스톤(`streak_N`) + 완벽한 날 연속 마일스톤(`perfect_N`) 달성 시 자동 잠금 해제 |
| 대시보드 | 최근 28일 히트맵(완벽/성공/초과 3단계), 업적 뱃지, 사용시간 막대그래프(7·14·30일 기간 선택, 분/한도 대비 % 전환), 시간대별(0~23시) 이용 패턴 그래프 |

수동 활동 기록(운동/공부 입력) 없음 — 전부 자동 판정. 레거시 버전에 있던 "화면 캡처해서 공부/놀기 AI로 구분" 기능은 이번 버전에서 드롭함.

## 프로젝트 구조

```
TubeLimiter/
├── extension/              ← 크롬 확장 (PC 브라우저 + 유튜브 PWA 커버)
│   ├── src/                ← 소스 (background, popup, options, dashboard, auth, lib)
│   ├── public/              ← manifest.json, html, 아이콘/css
│   ├── supabase/schema.sql  ← DB 테이블 + 보안 정책
│   └── README.md            ← 처음 설정 방법
├── android/                ← 네이티브 안드로이드 앱 (유튜브 앱 자체 차단). 계획: MOBILE_PLAN.md
└── documents/              ← 이 문서들
```

레거시(대회 제출) 버전은 저장소에서 제거됨 — 게임화 이전의 회피형 차단 로직만 있던 버전으로, 참고할 내용이 남아있지 않아 삭제.

## 백엔드(Supabase) 상태

Supabase 프로젝트는 이미 생성/연결되어 있음 — `schema.sql` 반영 완료, URL/anon key는 `extension/src/lib/config.js`와 `android/.../auth/SupabaseConfig.kt` 양쪽에 동일하게 박혀 있음 (같은 프로젝트를 두 클라이언트가 공유). 테이블 구조와 동기화 방식은 [BACKEND.md](BACKEND.md) 참고.

새로 이 프로젝트를 처음부터 세팅해야 하는 경우(예: 다른 Supabase 프로젝트로 옮길 때)만 아래가 필요:
1. supabase.com 프로젝트 생성 → SQL Editor에서 `extension/supabase/schema.sql` 실행.
2. 두 클라이언트의 config 파일(`extension/src/lib/config.js`, `android/app/src/main/java/com/tubelimiter/app/auth/SupabaseConfig.kt`)에 새 URL/anon key 반영.

확장 빌드/로드 절차는 `extension/README.md`, 안드로이드는 [MOBILE_PLAN.md](MOBILE_PLAN.md) 참고.

## 여러 세션/에이전트가 동시에 이 저장소 작업할 때

Claude Code 세션을 여러 개(다른 창, 원격 등) 같은 저장소에 띄워두면 같은 파일을 동시에 저장하다 내용이 섞일 수 있음 — 실제로 한 번 발생: 두 세션이 동시에 `daily_usage` 동기화 기능을 독립적으로 구현하다 `schema.sql`에 같은 함수가 두 번 들어가고, `service-worker.js`에 네이밍이 다른 구현 두 개가 섞인 채로 저장된 적 있음. 파일이 "마지막으로 읽은 뒤 바뀌었다"는 경고가 뜨거나 빌드가 중복 선언 에러를 내면 다른 세션이 같은 파일을 건드리고 있다는 신호 — 큰 작업 시작 전엔 다른 세션이 열려 있는지 확인하고, 겹치는 게 보이면 어느 쪽 구현을 남길지 먼저 정리하고 진행할 것.

## Manifest V3 참고 메모 (진행하며 챙긴 것들)

- **manifest_version 3 필수.** `permissions`(storage/tabs/scripting/alarms 등 크롬 API 권한)와 `host_permissions`(외부 URL 접근 권한, 여긴 `*://*.youtube.com/*`만 사용)는 분리해서 선언.
- **서비스워커는 계속 안 살아있음.** 일정 시간 idle이면 꺼짐 → 상태를 전역 변수에만 두면 날아감. 그래서 `chrome.storage.local`을 진짜 상태 저장소로 쓰고, `setInterval` 대신 `chrome.alarms`(1분 주기)로 주기 작업을 예약함 — 알람은 서비스워커가 꺼져 있어도 브라우저가 깨워서 실행해줌.
- **컨텍스트 분리:** content script(유튜브 페이지, DOM 접근 가능)와 background/popup/options(DOM 없음, chrome.* API 다 씀)는 서로 다른 실행 환경. 서로 대화하려면 메시지 패싱만 가능:
  - background/popup → content: `chrome.tabs.sendMessage(tabId, msg)`
  - content → background: `chrome.runtime.sendMessage(msg)`
  - 수신: `chrome.runtime.onMessage.addListener(...)`
- **CSP 제약:** html에 `<script>...</script>` 인라인 코드 금지, 항상 외부 .js 파일로 분리. `eval`이나 CDN에서 원격 코드 불러오기도 금지 — 그래서 Supabase 클라이언트도 CDN이 아니라 esbuild로 미리 번들링해서 로컬 파일로 포함시킴.

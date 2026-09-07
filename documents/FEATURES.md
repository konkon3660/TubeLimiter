# TubeLimiter v2 — 만든 것 요약

## 뭐가 바뀌었나

기존(레거시)은 "시간 넘으면 차단"만 하는 회피형 도구였음. 새 버전은 차단 로직은 유지하면서 그 위에 **계정 동기화 + 게임화(스트릭/XP/업적)** 를 얹음. 목적: 차단이 뚫려도 "꾸준히 지킨 기록"이 아까워서 안 보게 만들기.

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 시청 제한 + 차단 | 일일/요일별 한도 설정, 넘으면 유튜브 페이지에 오버레이로 차단 |
| 집중 모드 | 정해진 시간 동안 한도와 무관하게 즉시 차단. 진짜 커밋먼트 장치가 되도록 활성 세션을 도중에 끄려면 하드코어 모드 해제처럼 10분 대기(요청 후 취소 가능)가 필요하고, 그동안에도 긴급 시청으로 우회할 수 없음. 단, 아직 시작 전인 "지연(분)" 예약을 취소하는 건 즉시 처리됨 — 시작한 적이 없으니 대기가 의미 없음 |
| 예약 차단 | 요일·시간대를 등록해두면 그 시간엔 한도와 무관하게 자동으로 차단(예: 매일 22:00~07:00). 집중 모드와 같은 급이라 긴급 시청으로 우회할 수 없고 화이트리스트도 통하지 않음. 옵션(안드로이드는 설정 화면)에서만 추가·수정·삭제할 수 있고, 팝업/홈 화면에는 활성 여부만 읽기 전용으로 표시 — 끄는 버튼을 따로 두지 않아 진짜 커밋먼트 장치로 남음. 4시 사용량 컷오프와 무관하게 실제 시계 기준으로 판정(상세는 [BACKEND.md](BACKEND.md)) |
| 긴급 시청 | 차단 상태에서 5분간 예외 허용(사용 한도·수동 차단·Shorts 한도에만 적용, 집중 모드·예약 차단은 우회 불가), 횟수는 일/주/월 단위로 리셋. 남용 방지로 한 번 쓰면 15초간 재요청 불가. 그동안 본 시간은 총 시청시간에 그대로 포함되지만, 스트릭 판정에서는 빼주고(스트릭은 안 끊김) 그날의 "완벽한 날" 자격만 잃는다. **남은 횟수는 기기별이 아니라 계정 단위** — PC에서 3회를 다 쓰면 폰에서도 0회다. 안 그러면 기기 하나 바꾸는 걸로 커밋먼트 장치가 무력화된다(리셋 주기가 주/월이면 그 버킷 전체를 합산, 상세는 [BACKEND.md](BACKEND.md)) |
| 화이트리스트 / Shorts 차단 | 특정 URL 제외, Shorts 항상 차단 옵션. 둘 다 화면 내용을 봐야 판정되므로 **브라우저 전용** |
| Shorts 전용 일일 한도 | 전체 한도와 별개로 Shorts에만 거는 일일 한도("전체는 2시간, Shorts는 10분"). 넘으면 Shorts 페이지만 막히고 일반 영상(/watch)은 남은 전체 한도만큼 계속 볼 수 있다. 전체 한도와 같은 급의 한도라 긴급 시청·화이트리스트로는 뚫리고 집중 모드·예약 차단은 못 뚫는다. 하드코어 모드에서는 전체 한도와 똑같이 입력이 잠긴다 — 안 잠그면 Shorts 한도를 올리는 게 우회로가 된다. **브라우저 전용**(안드로이드는 앱 단위 감지라 Shorts 여부를 모름). 차단 우선순위 전체는 `extension/src/lib/blockDecision.js` 상단 주석과 [extension/README.md](../extension/README.md) 참고 |
| 계정 로그인 | 이메일 회원가입/로그인 (Supabase Auth) |
| 클라우드 동기화 | 설정/스트릭/오늘 사용량을 서버(Supabase)에 저장 → 확장·안드로이드 여러 기기에서 같은 계정으로 로그인하면 공유됨. 오늘 사용량은 절대값 덮어쓰기가 아니라 델타 합산(`increment_daily_usage` RPC)이라 기기별 몫이 안 사라짐 — 상세는 [BACKEND.md](BACKEND.md) |
| 자동 스트릭 | 자정마다 그날 사용량이 한도 이내였는지 판정 → 성공하면 연속일수 +1, 실패하면 0으로 리셋. 판정에서 긴급 시청 시간은 빼고 보고(긴급 시청은 스트릭을 안 끊음), 한도를 넘는 순간이 든 마지막 정산 구간(최대 1분)은 측정 단위 때문에 생기는 초과라 봐준다 |
| 완벽한 날 | 긴급 시청을 한 번도 안 쓰고 한도를 지킨 날. 스트릭과 별도로 누적일수·연속기록을 세고(`perfect_days`, `current_perfect_streak`), 긴급 시청을 쓴 날엔 스트릭은 이어지지만 이쪽만 0으로 끊긴다 — 긴급 시청을 벌하지 않으면서도 "안 쓴 날"은 따로 보상하는 이원화 |
| XP / 레벨 | 그날 안 쓴 시간 10분당 1XP(무제한 설정이면 24시간 기준) + 스트릭을 이어간 날은 그날의 스트릭 일수만큼 + 완벽한 날이면 +10XP + 마일스톤 보너스(달성 일수 × 5). 마일스톤은 3·7·14·30·60·100·365일 스트릭과 7·30·100일 완벽 연속. 레벨은 코스메틱(색상/칭호)만 바꾸고 한도·긴급 시청 같은 실질 기능엔 영향 없음 |
| 업적 뱃지 | 스트릭 마일스톤(`streak_N`) + 완벽한 날 연속 마일스톤(`perfect_N`) 달성 시 자동 잠금 해제 |
| 대시보드 | 최근 28일 히트맵(완벽/성공/초과 3단계), 업적 뱃지, 사용시간 막대그래프(7·14·30일 기간 선택, 분/한도 대비 % 전환), 시간대별(0~23시) 이용 패턴 그래프. 히트맵·막대그래프는 로컬 기록과 서버 `daily_usage`를 날짜별 `max(로컬, 서버)`로 병합해 그린다 — 재설치하거나 두 번째 기기에서 로그인해도 비어 보이지 않게. 시간대별 그래프만 로컬 전용(서버에 시간대 단위 기록이 없음). 과거 날짜는 **그날 실제로 적용됐던 한도**로 판정한다(아래 참고). 서버 병합과 한도 스냅샷은 아직 확장에만 있음 — 안드로이드 대시보드는 로컬 기록 + 현재 한도로 그린다([MOBILE_PLAN.md](MOBILE_PLAN.md) 대응표) |
| 그날의 한도 스냅샷 | 히트맵/막대그래프가 과거를 지금 설정으로 소급 판정하면, 한도를 30분→2시간으로 올리는 것만으로 예전 실패들이 한꺼번에 성공으로 바뀐다. 그래서 그날 적용된 한도를 날짜별로 남기고(확장 `limit_history`, 60일 보관) 그 값으로 판정한다. 스냅샷이 없는 날(이 기능 이전, 또는 다른 기기에서만 시청해 서버에만 있는 날 — 한도는 날짜별로 서버에 올라가지 않는다)만 현재 설정으로 근사 판정하고, 그런 날은 툴팁에 추정이라고 밝힌다. 확장 전용 |
| 동기화 진단 로그 | 동기화·인증 실패를 기기 안에만 남기는 50건 링버퍼(확장은 옵션 페이지, 안드로이드는 설정 화면에 복사/지우기 버튼과 함께). 실패만 쌓고 성공은 시각만 갱신하며, 마지막 성공이 24시간을 넘으면 팝업/홈에 조용한 한 줄 경고가 뜬다. 외부 크래시 리포팅을 붙이지 않기로 했기 때문에 그 자리를 메우는 장치 — 서버로 올라가지 않고, 민감정보는 애초에 들어가지 않게 막는다(계약은 [BACKEND.md](BACKEND.md)) |
| 다국어 (한국어/영어) | 확장은 `extension/public/_locales/{ko,en}/messages.json` 242키 + `default_locale: ko`, 안드로이드는 `res/values/`(한국어) + `res/values-en/`. 문구를 추가할 땐 **양쪽 로케일에 다 넣어야** 하고, 판정 순수 함수는 문구 대신 키·enum을 돌려준다 — 상세는 [extension/README.md](../extension/README.md), [ANDROID_SETUP.md](ANDROID_SETUP.md) |

수동 활동 기록(운동/공부 입력) 없음 — 전부 자동 판정. 레거시 버전에 있던 "화면 캡처해서 공부/놀기 AI로 구분" 기능은 이번 버전에서 드롭함.

## 프로젝트 구조

```
TubeLimiter/
├── extension/              ← 크롬 확장 (PC 브라우저 + 유튜브 PWA 커버)
│   ├── src/                 ← 소스 (background, content, popup, options, dashboard, auth, lib)
│   ├── public/              ← manifest.json, html, 아이콘/css, _locales/{ko,en}
│   ├── test/                ← node:test (src/lib의 순수 함수 대상)
│   ├── supabase/            ← schema.sql(테이블+RLS) + functions/delete-account
│   └── README.md            ← 처음 설정·빌드·i18n·진단 로그
├── android/                ← 네이티브 안드로이드 앱 (유튜브 앱 자체 차단). 계획: MOBILE_PLAN.md
├── docs/                   ← GitHub Pages (랜딩 + 개인정보처리방침)
├── .github/workflows/      ← CI (확장 lint+test, 안드로이드 유닛 테스트)
└── documents/              ← 이 문서들
```

레거시(대회 제출) 버전은 저장소에서 제거됨 — 게임화 이전의 회피형 차단 로직만 있던 버전으로, 참고할 내용이 남아있지 않아 삭제.

## 백엔드(Supabase) 상태

Supabase 프로젝트는 이미 생성/연결되어 있음 — `schema.sql` 반영 완료, URL/anon key는 `extension/src/lib/config.js`와 `android/.../auth/SupabaseConfig.kt` 양쪽에 동일하게 박혀 있음 (같은 프로젝트를 두 클라이언트가 공유). 테이블 구조와 동기화 방식은 [BACKEND.md](BACKEND.md) 참고.

새로 이 프로젝트를 처음부터 세팅해야 하는 경우(예: 다른 Supabase 프로젝트로 옮길 때)만 아래가 필요:
1. supabase.com 프로젝트 생성 → SQL Editor에서 `extension/supabase/schema.sql` 실행.
2. 두 클라이언트의 config 파일(`extension/src/lib/config.js`, `android/app/src/main/java/com/tubelimiter/app/auth/SupabaseConfig.kt`)에 새 URL/anon key 반영.

확장 빌드/로드·패키징 절차는 [extension/README.md](../extension/README.md), 안드로이드 빌드/서명은 [ANDROID_SETUP.md](ANDROID_SETUP.md)(설계 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md)) 참고.

## CI

`.github/workflows/ci.yml`이 push(main)와 모든 PR에서 두 잡을 병렬로 돌린다 — 확장(Node 20: `npm ci` → `npm run lint` → `npm test`)과 안드로이드(temurin JDK 17 + `platforms;android-37` 설치 후 `./gradlew testDebugUnitTest`). 테스트 파일은 진작 있었는데 아무도 돌리지 않던 상태를 메우려고 붙였다.

**안드로이드 잡은 아직 한 번도 실행되지 않아 미검증이다** — SDK 채널에 `android-37`이 아직 없거나 AGP 9가 JDK 21 툴체인을 요구하면 그 잡에서 드러난다. 첫 실행 결과를 보고 고쳐야 한다. 잡별 상세는 [extension/README.md](../extension/README.md), [ANDROID_SETUP.md](ANDROID_SETUP.md).

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

# TubeLimiter v2 (확장 프로그램)

일일 시청 제한 + 차단은 유지하되, 그 위에 계정 로그인/서버 동기화와 자동 스트릭·XP·업적 게임화를 얹은 재작성 버전.
기존 대회 제출(레거시) 버전은 저장소에서 제거됨 — 자세한 배경은 `../documents/FEATURES.md`.

## 처음 설정

### 1. Supabase 프로젝트 준비 (계정 필요, 무료 티어로 충분)
1. https://supabase.com 에서 프로젝트 생성.
2. 좌측 메뉴 **SQL Editor** 에서 `supabase/schema.sql` 내용을 그대로 붙여넣고 실행 → 테이블 4개(`daily_usage`, `settings`, `streaks`, `achievements`) + RLS 정책 생성됨.
3. **Authentication > Providers**에서 Email 로그인이 켜져 있는지 확인 (기본값 켜짐). 로컬 테스트를 빨리 하고 싶으면 **Authentication > Settings**에서 "Confirm email" 옵션을 꺼도 됨(운영 배포 시에는 다시 켜는 걸 권장).
4. **Project Settings > API**에서 `Project URL`과 `anon public` 키를 복사.

### 2. 코드에 키 채우기
`src/lib/config.js` 를 열어 `SUPABASE_URL`, `SUPABASE_ANON_KEY`를 위에서 복사한 값으로 교체.
(anon key는 RLS로 보호되는 공개 키라 커밋해도 안전함.)

### 3. 빌드
```
npm install
npm run build
```
`dist/` 폴더가 실제 로드할 확장 프로그램 결과물. `npm run watch`로 변경 감지 빌드 가능.

그 외 스크립트:

| 명령 | 하는 일 |
|---|---|
| `npm test` | `node --test`로 `test/` 전체 실행. 판정 로직은 전부 `src/lib/` 순수 함수라 브라우저 없이 돈다 |
| `npm run lint` | ESLint(플랫 설정 `eslint.config.js`). 스타일 규칙은 끄고 실제 오류가 되는 규칙만 켜 둠 — 포맷 취향으로 빌드를 깨지 않으려고 |
| `npm run format` / `format:check` | Prettier. 린트와 분리돼 있지만 CI는 둘 다 본다 — 확장 잡이 `lint` → `format:check` → `test` 순으로 돈다 |
| `npm run zip` | 스토어 업로드용 패키징. 소스맵 없이 빌드한 뒤 `dist/`를 `release/tubelimiter-<manifest 버전>.zip`으로 압축한다. 버전은 `public/manifest.json`의 `version`이 유일한 원본이고, zip 루트에 `manifest.json`이 바로 오도록 담는다(웹스토어가 요구하는 모양) |

`dist/`와 `release/`는 둘 다 gitignore 대상 — 빌드 산출물은 커밋하지 않는다.

### 4. 크롬에 로드
1. `chrome://extensions` → 개발자 모드 켜기.
2. "압축해제된 확장 프로그램을 로드합니다" → `extension/dist` 폴더 선택.
3. 툴바 아이콘 클릭 → "로그인/회원가입"으로 계정 생성 → 옵션에서 한도 설정.

## 구조

```
extension/
├── public/            # manifest.json, html, 정적 assets (그대로 dist/에 복사됨)
│   └── _locales/      # ko/en messages.json — 화면 문구는 전부 여기 (아래 "다국어" 참고)
├── src/
│   ├── lib/           # 판정 순수 함수 + 저장소 래퍼. chrome.* 없이 node:test로 도는 파일들:
│   │                  #   blockDecision / alarmRules / dateRollover / limits / limitHistory /
│   │                  #   historyMerge / usageMerge / schedule / emergency / focusMode /
│   │                  #   hardcore / gamification / time / syncDiagnostics / i18n
│   ├── background/    # service worker: 탭 추적, 판정 호출, 자정 롤오버, Supabase 동기화
│   ├── content/       # 유튜브 페이지 차단 오버레이
│   ├── popup/         # 툴바 팝업 (오늘 사용량, 스트릭, 집중모드, 긴급시청)
│   ├── options/       # 설정 페이지 (한도/Shorts 한도/화이트리스트/긴급시청/진단 로그)
│   ├── dashboard/     # 통계+스트릭 캘린더+업적 뱃지 (Chart.js)
│   └── auth/          # 로그인/회원가입 페이지
├── test/              # node:test. src/lib/*와 1:1 대응
├── supabase/schema.sql
├── eslint.config.js   # 플랫 ESLint 설정
└── build.mjs          # esbuild 번들 + 정적 복사 + --zip 패키징
```

`service-worker.js`는 원래 판정과 `chrome.storage`/Supabase 호출을 한 파일에 섞어놔서 1000줄 넘게 유닛 테스트가 하나도 안 붙었다. 지금은 안드로이드 쪽 구조("판정은 값을 반환하고 저장은 호출자가 한다")를 따라 `lib/blockDecision.js`(차단 우선순위·탭 단위 예외), `lib/alarmRules.js`(주기/마일스톤 알림), `lib/dateRollover.js`(날짜 롤오버·긴급 횟수 버킷 리셋)로 판정만 떼어냈다. 이 파일들의 규칙을 고치면 대응하는 안드로이드 파일(`limit/BlockDecision.kt`, `limit/AlarmRules.kt`)도 같이 고쳐야 한다 — 계약은 [../documents/BACKEND.md](../documents/BACKEND.md).

차단 우선순위(위가 셈, `lib/blockDecision.js` 상단 주석이 원본):

```
집중 모드 > 예약 차단 > 긴급 시청(우회) > 화이트리스트(우회) > Shorts 항상 차단
  > 수동 차단 > 전체 한도 > Shorts 한도
```

집중 모드와 예약 차단은 "한도와 무관하게" 막는 커밋먼트 장치라 긴급 시청으로도 화이트리스트로도 못 뚫는다(긴급 시청 발급 자체가 거부됨). 반대로 Shorts 한도는 전체 한도와 같은 급의 평범한 한도라 긴급 시청·화이트리스트로 뚫린다. 앞의 다섯 줄은 안드로이드 `BlockInputs.blockReason()`과 순서가 같고, 화이트리스트/Shorts 두 줄은 화면 내용을 봐야 판정되므로 브라우저 전용이다.

## 게임화 규칙

규칙의 원본은 `src/lib/gamification.js`이고(안드로이드 `gamification/Gamification.kt`가 같은 규칙의 포팅본), 왜 그렇게 정했는지는 [../documents/FEATURES.md](../documents/FEATURES.md)에 있다. 여기엔 코드에서 바로 읽히는 값만 적는다.

- **성공 판정**(`isDaySuccess`): 자정(4시 컷오프) 롤오버 시 **긴급 시청으로 본 시간을 빼고 나서** 그날 한도 이내면 성공. 긴급 시청은 남은 횟수를 소모해서 산 허용된 예외라 그걸로 스트릭까지 끊으면 이중 처벌이라는 판단. 총 사용시간 자체는 사실대로 기록되므로 빼주지 않으면 긴급 시청을 쓴 날은 자동으로 실패가 된다. 측정 단위(1분 틱) 때문에 생기는 최대 1분 초과는 봐준다(`TRACKING_TICK_GRACE_MS`).
- **완벽한 날**(`isPerfectDay`): 성공한 날 중에서도 긴급 시청을 **한 번도 쓰지 않은** 날(시간·횟수 둘 다 0). 긴급 시청을 쓴 날은 `current_streak`은 이어지고 완벽한 날 연속기록만 0으로 끊긴다.
- **스트릭**: 연속 성공일수. 실패하면 0으로 리셋(누적 성공일수/최고 기록은 유지). 완벽한 날 스트릭도 같은 방식으로 따로 관리.
- **XP**: 그날 안 쓴 시간 10분당 1XP(`unusedTimeXpBonus`, 무제한 설정이면 24시간 기준) + 스트릭을 이어간 날은 그날의 스트릭 일수만큼 + 완벽한 날이면 +10XP + 마일스톤 보너스(일수 × 5).
- **레벨**: 삼각수 누적 공식 (`getLevelProgress`). 레벨은 코스메틱(색상/칭호)만 바꾸고 한도·긴급 시청 같은 실질 기능엔 영향 없음.
- **업적**: 스트릭 마일스톤 `streak_N`(3·7·14·30·60·100·365)과 완벽한 날 연속 마일스톤 `perfect_N`(7·30·100)이 각각 자동 잠금 해제되어 대시보드에 뱃지로 뜬다.

## 다국어(i18n)

화면 문구는 전부 `public/_locales/<로케일>/messages.json`에 있고 코드에는 키만 남는다. 로케일은 `ko`/`en` 두 개, `manifest.json`의 `default_locale`은 `ko`(대응 로케일이 없는 브라우저는 한국어로 떨어진다).

**문구를 추가/수정할 때 지켜야 할 것:**

1. **`ko`와 `en` 양쪽에 같은 키를 넣는다.** 한쪽에만 넣으면 다른 로케일에서 그 자리가 빈다. `test/i18n.test.js`가 두 로케일의 키 집합을 비교하고, HTML의 `data-i18n` 키가 실제로 존재하는지, 반대로 아무도 안 쓰는 고아 키가 없는지, `$1` 같은 치환 자리 개수가 두 로케일에서 같은지까지 본다 — `npm test`가 잡아준다.
2. **HTML 정적 문구는 `data-i18n="키"`로 적는다.** 속성에 넣어야 하면 `data-i18n-placeholder` / `data-i18n-title` / `data-i18n-empty`(CSS `content: attr(data-empty)`용). 페이지 스크립트가 맨 처음에 `applyI18n()`을 부른다 — MV3 CSP가 인라인 `<script>`를 금지해서 치환도 외부 스크립트에서만 할 수 있다.
3. **개수가 들어가는 문구는 문자열을 이어붙이지 말고 `tCount()`를 쓴다.** `chrome.i18n`에는 복수형 기능이 없어서 `<키>_one` / `<키>_other` 두 벌을 두고 `pluralMessageKey()`가 고른다. 한국어는 수 일치가 없지만 영어는 갈리고, 어순도 언어마다 달라 조각을 `+`로 잇는 순간 한 언어의 어순이 박제된다.
4. **판정 순수 함수는 문구를 돌려주지 않는다.** `lib/blockDecision.js`는 `BLOCK_REASON` enum, `lib/alarmRules.js`는 `{kind, minutes}`, `lib/syncDiagnostics.js`는 메시지 키와 `StaleSyncReason`, `lib/gamification.js`의 레벨 티어는 `key`만 돌려준다. 이유는 두 가지 — 이 파일들은 `chrome.*` 없이 `node:test`에서 돌아야 하는데 `chrome.i18n`을 부르는 순간 못 돌고, 문구가 로직 안에 들어가면 번역할 자리가 코드 전체로 흩어진다. 문구를 붙이는 건 항상 UI(팝업/옵션/대시보드/서비스워커) 쪽이다.

## 동기화 진단 로그

MV3 서비스워커는 유휴 상태가 되면 죽고 devtools 콘솔도 같이 날아가서, `console.error`로만 남기던 동기화 실패는 사실상 흔적이 없었다 — 며칠째 Supabase 동기화가 막혀 있어도 알 방법이 없다. 외부 크래시 리포팅을 붙이지 않기로 했으므로 그 자리를 로컬 링버퍼가 메운다.

- 최근 **50건**(`DIAGNOSTIC_CAPACITY`)만 `chrome.storage.local`에 남는다. 실패만 쌓이고 성공은 시각(`last_sync_success_at`)만 갱신 — 성공까지 넣으면 버퍼가 노이즈로 찬다. 같은 (종류 + 코드)가 반복되면 줄을 쌓지 않고 카운터를 올린다.
- 마지막 성공이 **24시간**(`SYNC_STALE_THRESHOLD_MS`)을 넘으면 팝업에 조용한 한 줄 경고가 뜬다. 옵션 페이지에 접기 가능한 패널 + 복사/지우기 버튼이 있고, 로그아웃·계정 삭제 시 같이 지워진다.
- 이벤트 종류(`DiagnosticKind`)와 50건·24시간 값은 **안드로이드와 문자열까지 같아야 한다** — 기준이 갈라지면 "폰은 멀쩡한데 크롬만 이상하다"는 판단 자체가 안 된다. 계약은 [../documents/BACKEND.md](../documents/BACKEND.md).

**민감정보는 절대 넣지 않는다.** 이 버퍼는 사용자가 "복사" 버튼으로 클립보드에 담아 남에게 붙여넣는 걸 전제로 하므로, 한 번 새면 그대로 유출이다. 방어는 두 겹이고 **2번은 1번의 대체재가 아니다**:

1. `summarizeFailure()`가 **허용 목록** 방식으로 오류 이름 + `[45]xx` HTTP 상태 + PostgREST 코드(`PGRSTxxx`)만 뽑는다. PostgREST/GoTrue 오류 문구에는 조건에 걸린 값(이메일, uuid)이 그대로 실려 오므로 **서버 메시지 본문은 한 글자도 옮기지 않는다.**
2. `sanitizeDiagnosticCode()`가 이메일·UUID·JWT 모양을 `[redacted]`로 지우고 48자로 자른다. 이벤트를 넣는 유일한 통로인 `appendDiagnosticEvent()`가 항상 이걸 거친다.

새 실패 경로를 진단에 붙일 때도 반드시 이 두 함수를 통과시켜라. 원문 문자열을 직접 `code`에 넣으면 위 계약이 깨진다.

## CI

`.github/workflows/ci.yml`의 `extension` 잡이 push(main)와 모든 PR에서 Node 20으로 `npm ci` → `npm run lint` → `npm test`를 돌린다. 테스트 파일이 진작 있었는데 정작 아무도 돌리지 않던 상태를 메우려고 붙였다. 같은 워크플로의 안드로이드 잡(현재 미검증)은 [../documents/ANDROID_SETUP.md](../documents/ANDROID_SETUP.md) 참고.

## 알려진 제약

- `chrome.alarms`는 패키징된(스토어 배포) 확장에서 1분 미만 주기를 강제로 1분으로 올림 처리함 → 사용 시간 기록/차단 판정 해상도가 최대 약 1분. (탭 전환/URL 변경 시점에는 즉시 재계산되므로 체감 지연은 적음.)
- 대시보드 히트맵/막대그래프는 로컬 기록과 서버 `daily_usage`를 **날짜별 `max(로컬, 서버)`** 로 합쳐서 그린다(`lib/historyMerge.js`) → 재설치하거나 두 번째 기기에서 로그인해도 히트맵이 비어 보이지 않는다. 반면 **시간대별(0~23시) 그래프는 로컬 전용** — 서버에 시간대 단위 기록이 아예 없어서다(페이지에도 그렇게 적혀 있다). 로그아웃·오프라인·조회 실패면 로컬 기록만으로 그린다. 긴급 시청 **시간**은 병합되지만 **횟수**는 로컬 값만 쓰므로, 서버에만 있는 날은 툴팁에 횟수를 적지 않는다(0회로 채우면 "긴급 시청 없이 넘긴 날"이라는 없는 사실을 적게 된다).
- 히트맵/막대그래프는 그날 실제로 적용됐던 한도를 `limit_history`(날짜별 스냅샷, 60일 보관)에 남겨 그 값으로 판정함 → 나중에 한도를 바꿔도 과거 판정이 안 흔들림. 다만 **이 기능 이전 날짜와 다른 기기에서만 시청한 날**은 스냅샷이 없어(한도는 서버로 동기화되지 않음) 여전히 현재 설정된 한도로 근사 판정하고, 그런 날은 툴팁에 "(현재 설정 기준 추정)"으로 표시함.
- Android 앱은 별도 저장소 경로(`../android/`)에서 진행 중 — 계획/현황은 `../documents/MOBILE_PLAN.md` 참고.

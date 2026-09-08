# 모바일 앱 개발 계획 (Android, 개인용)

## 전제

- **Android만.** iOS 미지원 (기기 없음, 테스트 불가).
- **개인용.** Play Store 배포 안 함, 사이드로드(APK 직접 설치)로만 사용. → Play 정책(Accessibility API 사용 근거 심사 등) 신경 안 써도 됨.
- 스택: **네이티브 Kotlin**. 이유는 [[PLATFORM_PLAN]] 참고 — 핵심 기능(사용시간 감지)이 어차피 네이티브 API라 React Native 써도 이득이 적음.

## 핵심 기술 선택

### 사용시간 감지: UsageStatsManager vs AccessibilityService

| 방식 | 권한 | 정확도 | 배터리 | 비고 |
|---|---|---|---|---|
| `UsageStatsManager` | `PACKAGE_USAGE_STATS` (설정 앱에서 수동 허용, 런타임 다이얼로그 아님) | `queryEvents()`로 MOVE_TO_FOREGROUND/BACKGROUND 이벤트 단위 추적 가능 | 낮음 (주기적 폴링) | 권장 |
| `AccessibilityService` | 접근성 서비스 수동 활성화 | 실시간, 창 전환 즉시 감지 | 높음 | 개인용이라 Play 정책 문제는 없지만 오버스펙 |

→ **UsageStatsManager 우선.** WorkManager나 Foreground Service에서 주기적으로 `queryEvents()` 폴링해서 유튜브 앱(`com.google.android.youtube`) foreground 구간 누적.

### 차단 오버레이

- `SYSTEM_ALERT_WINDOW` 권한 (Android 6+ 부터 설정에서 수동 허용 필요, 런타임 다이얼로그로 유도 가능).
- 한도 초과 + 유튜브 앱 foreground 감지되면 `TYPE_APPLICATION_OVERLAY` 창 띄워서 차단.

### 백그라운드 유지

- **Foreground Service 필수.** Android 8(Oreo)부터 백그라운드 서비스 제약 심함 — 지속 알림 띄우는 Foreground Service로 감지 로직 상주.
- **Android 14(API 34)+**: Foreground Service Type 매니페스트에 명시 필수 (`dataSync` 또는 `specialUse` 검토).
- **배터리 최적화 예외** 요청 (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) — 안 하면 Doze 모드에서 폴링 주기 밀림.
- **알림 권한**: Android 13(API 33)+부터 `POST_NOTIFICATIONS` 런타임 권한 필요 (Foreground Service 알림 표시용).

## 백엔드 연동

- 기존 Supabase 프로젝트 그대로 재사용 (계정/스트릭/XP 공유). 테이블별 계약/동기화 방식은 [BACKEND.md](BACKEND.md) 참고.
- `supabase-kt`(Ktor 기반) 사용 또는 Postgrest REST 직접 호출. 스키마(`extension/supabase/schema.sql`)와 RLS 정책 그대로 적용됨 — 추가 마이그레이션 불필요.
- 오프라인 대비: 로컬 캐시(Room 또는 DataStore)에 그날 사용량 저장 → 네트워크 복구 시 동기화.

## UI

- Jetpack Compose 권장 (XML View보다 보일러플레이트 적음, 대시보드 그래프 등 붙이기 편함).
- 로그인 화면은 기존 확장 UX 참고해서 이메일/비번 폼으로.

## 개발 시 명심할 것

1. **권한 4종을 앱 최초 실행 시 순서대로 유도해야 함**: 사용현황 접근(설정으로 이동) → 오버레이 표시 → 배터리 최적화 제외 → 알림. 하나라도 빠지면 핵심 기능 무력화됨. 온보딩 화면에서 체크리스트로 보여주는 게 안전.
2. **UsageStatsManager 권한은 런타임 다이얼로그가 아니라 설정 화면으로 유도**만 가능 (`Settings.ACTION_USAGE_ACCESS_SETTINGS`) — 사용자가 직접 앱 찾아서 켜야 함. 개인용이라도 껐다 켰을 때 재확인 로직 필요.
3. **기기 하나로만 테스트하는 상황** — 프로덕션처럼 매일 쓰는 폰에 개발 빌드 올리는 거라, 빌드 깨지면 바로 일상 사용에 지장. 디버그 빌드와 별도로 안정 APK 하나는 항상 백업해두고, 큰 변경은 별도 빌드로 먼저 확인 후 교체.
4. **에뮬레이터로는 UsageStats 정확도 검증 어려움** — 실제 기기에서만 유의미하게 테스트 가능. 로직 유닛 테스트는 순수 함수로 분리해서(시간 계산, 스트릭 판정 등) 기기 없이도 검증 가능하게 짤 것.
5. **OEM별 백그라운드 킬 정책 차이** (삼성/샤오미 등 강한 배터리 절전 앱들이 Foreground Service도 죽이는 경우 있음) — 본인 기기 제조사 기준으로 "자동 실행 허용" 같은 제조사 전용 설정도 확인 필요.
6. **targetSdk는 최신 유지하되 API 레벨별 동작 변화 주의** — Foreground Service Type(34+), 알림 권한(33+), 백그라운드 제약(26+) 등 매 메이저 버전마다 규칙 바뀜. 본인 기기 Android 버전 먼저 확인하고 그 버전 기준으로 최소 동작 맞춘 뒤 상위 호환 고려.
7. **Google Play Protect 경고**: 사이드로드 + UsageStatsManager/오버레이 쓰는 앱은 "출처를 알 수 없는 앱" 경고나 Play Protect 스캔 경고 뜰 수 있음. 개인 서명 APK라 정상, 무시하고 진행.

## 배포 (Play Store, 토이 프로젝트로 시도)

사이드로드에서 Play Store로 방향 바뀌면 아래 항목들이 새로 붙음.

### 계정/비용

- Play Console 개발자 계정: **$25 (일회성)**.

### 민감 권한 심사 — 여기가 핵심 걸림돌

이 앱은 **`PACKAGE_USAGE_STATS`(사용현황 접근)** 와 **`SYSTEM_ALERT_WINDOW`(다른 앱 위에 표시)** 둘 다 씀 — Play Console에서 "권한 승인(Permissions Declaration)" 양식 제출 필수. 심사관이 "왜 이 권한이 꼭 필요한지" 앱 목적과 매칭해서 봄. 유튜브 사용시간 제한 앱이라 논리적으로 정당하긴 한데:

- 개인 개발자, 사용자 거의 없는 앱이라 **자동/수동 리젝 가능성 있음** — 근거 문서/스크린샷/영상 준비 필요.
- (Accessibility Service는 애초에 안 쓰기로 했으니 그쪽 심사는 회피됨 — 잘한 선택.)

### 신규 개인 개발자 계정 정책 (2023~ 시행 중)

- **Production(전체 공개) 트랙 가려면**: Closed Testing에서 **테스터 12명 이상이 14일 연속 옵트인** 상태 유지해야 함. 혼자 쓰는 토이 프로젝트에 테스터 12명 모으기 사실상 오버스펙.

### 현실적 경로 → Internal Testing 트랙 추천

- **Internal Testing**: 테스터 최대 100명(이메일 초대), 심사 거의 없음(권한 승인 양식은 여전히 필요할 수 있음, 확인 필요), 링크로 바로 설치 가능.
- 본인만 쓸 거면 Internal Testing으로 충분 — Production 갈 필요 없음. "배포해봤다"는 경험치는 이걸로도 채워짐.
- 나중에 정말 Production 올리고 싶으면 그때 테스터 모으는 거 고민.

### 추가로 필요해지는 것

1. **개인정보처리방침(Privacy Policy) 페이지** — URL 하나 필요 (GitHub Pages로 정적 페이지 하나 만들면 충분). Supabase에 이메일/사용기록 저장하니까 이거 명시해야 함.
2. **Data Safety 양식** — 수집 데이터(이메일, 유튜브 사용시간 기록) 종류/목적/제3자 공유 여부 선언.
3. **앱 서명**: Play App Signing 사용 (업로드 키 별도 관리).
4. **targetSdk**: 현재 정책 기준 최신에 가까운 API 레벨 요구됨 (제출 시점 최소 요구 레벨 Play Console에서 확인).

### 결론

바로 Production 노리지 말고 **Internal Testing까지만** 목표로 잡는 게 토이 프로젝트 규모에 맞음. 권한 승인 양식 제출은 Internal Testing 단계에서도 필요한지 실제로 콘솔 들어가서 확인 필요 — 안 걸리면 그대로 진행, 걸리면 근거자료 준비.

## 다음 단계

1. ~~프로젝트 스캐폴딩 (Kotlin + Compose).~~ 완료 — `android/`.
2. ~~권한 온보딩 플로우.~~ 완료 — 4종 순차 체크리스트, `onResume`마다 재확인.
3. ~~UsageStatsManager 폴링.~~ 완료 — 실기기에서 감지 확인됨. 하루 경계는 24시간 룩백으로 이전 상태를 판정해 `startsInForeground`로 넘김.
4. ~~오버레이 차단.~~ 구현 완료 — Foreground Service(`UsageMonitorService`)가 폴링, 한도 초과 + 유튜브 foreground면 `BlockOverlay` 표시. **실기기 검증 미완**.
5. ~~확장 기능 포팅.~~ 완료 — Supabase 동기화(인증 + 네트워크) 포함.
6. ~~릴리스 빌드 준비.~~ 완료 — 버전을 `gradle.properties`로 빼고, 릴리스 서명은 커밋하지 않는 `keystore.properties`에서 읽으며(없으면 미서명 빌드), R8 축소 + kotlinx.serialization keep 룰. 절차는 [ANDROID_SETUP.md](ANDROID_SETUP.md).
7. ~~ktlint 게이트.~~ 완료 — 처음엔 기존 위반 22건 때문에 `ignoreFailures = true`로 들어왔고, `ktlintFormat`으로 backlog를 비운 뒤 `false`로 뒤집었다. 이제 위반 하나에 빌드가 깨진다.
8. ~~유닛 테스트 CI.~~ 붙임 — `.github/workflows/ci.yml`. 안드로이드 잡은 `main`에서 실제로 돌아 **통과**했다(ktlint + 유닛 테스트 + `assembleRelease`). 첫 실행이 두 번 깨진 원인과 SDK 패키지 이름 함정은 [ANDROID_SETUP.md](ANDROID_SETUP.md)의 "CI".
9. ~~진단 로그 / 다국어.~~ 완료 — 위 대응표 참고.

남은 것:

- **오버레이 실기기 검증** (위 4번). 에뮬레이터로는 `UsageStatsManager` 정확도를 못 봐서 여전히 미완.
- ~~**CI 안드로이드 잡 첫 실행 확인**~~ — 확인됨. SDK 설치 스텝에서 두 번 깨진 뒤 세 게이트 모두 통과한다([ANDROID_SETUP.md](ANDROID_SETUP.md)의 "CI"). 대신 남은 것은 **계측 테스트가 0개**라는 사실이다 — `androidTest` 디렉터리 자체가 없어 오버레이·권한·서비스 생존은 CI가 손대지 못한다([FEATURES.md](FEATURES.md)의 "테스트가 보장하는 것과 아닌 것").
- **릴리스 APK 실기기 확인.** R8 축소가 켜진 빌드는 디버그와 다른 코드다(ktor 엔진 keep 룰이 없으면 릴리스에서만 네트워크가 죽는 것을 실제로 확인했다 — [ANDROID_SETUP.md](ANDROID_SETUP.md)). 배포 전에 릴리스 빌드로 로그인·동기화가 도는지 한 번은 봐야 한다.

### 확장 대비 기능 대응표

| 확장 기능 | 안드로이드 | 비고 |
|---|---|---|
| 일일 한도 / 요일별 한도 | 완료 | 요일 인덱스는 확장과 동일하게 일=0 기준 저장 |
| 새벽 4시 하루 경계 | 완료 | `lib/time.js`의 `DAY_CUTOFF_HOUR` 포팅 |
| 집중 모드 (지연 시작 포함) | 완료 | |
| 긴급 시청 5분 (일/주/월 리셋) | 완료 | 차단 오버레이에서 바로 사용. 남은 횟수는 계정 단위 — 다른 기기가 쓴 몫을 빼고 보여주고, 게이트는 `consumeEmergency`의 edit 트랜잭션 안에서 읽어 원자적으로 유지. 버킷 합산 규칙은 [BACKEND.md](BACKEND.md) |
| 수동 차단 | 완료 | |
| 알림 (N분 주기 / 남은 30·10·5·1분) | 완료 | |
| 하드코어 모드 + 1시간 해제 쿨다운 | 완료 | 해제 시 연속 기록 0으로 초기화까지 동일 |
| 스트릭 / XP / 레벨 / 뱃지 | 완료 | 레벨 공식·XP 배분 모두 동일. 긴급 시청 시간을 스트릭 판정에서 빼고 "완벽한 날"(긴급 시청 0회)을 따로 세는 규칙도 `Gamification.kt`에 같이 포팅됨 |
| 대시보드 (28일 히트맵, 14일 막대) | 완료 | Compose 자체 구현, 차트 라이브러리 없음 |
| 대시보드에 서버 기록 병합 | 완료 | `sync/HistoryMerge.kt`가 확장 `lib/historyMerge.js`와 같은 규칙(날짜별 `max(로컬, 서버)`, 합산 아님)으로 최근 30일을 합친다. 로그아웃·오프라인·조회 실패면 조용히 로컬만 그린다. 긴급 시청 **횟수**도 병합 — 확장과 동일 |
| 그날 적용된 한도 스냅샷 | 완료 | `limit/LimitHistory.kt`가 `limit_history`에 날짜별 한도를 남기고, 히트맵과 **스트릭 정산** 둘 다 그 값으로 판정한다(`planRolloverLimits`, 확장과 같은 이름·같은 규칙 — `streaks` 행을 공유하므로 갈라지면 안 됨). 스냅샷 없는 날은 현재 설정으로 근사하고 히트맵에서 외곽선 + 범례로 추정임을 밝힌다(Compose 셀엔 툴팁이 없어서) |
| 계정 로그인/로그아웃 | 완료 | `supabase-kt`. 확장과 같은 프로젝트·같은 계정 |
| 클라우드 동기화 | 완료 | `settings`/`streaks`/`achievements`는 `SyncRepository`로 pull/push. `daily_usage`는 `increment_daily_usage` RPC로 델타 합산 (documents/BACKEND.md 참고) |
| 동기화 진단 로그 | 완료 | DataStore 50건 링버퍼(`diagnostics/SyncDiagnostics.kt`), 설정 화면에 접기 카드 + 복사/지우기, 마지막 성공이 24시간을 넘으면 홈에 한 줄 경고. 이벤트 종류·버퍼 크기·임계값은 확장과 **문자열까지 동일해야 하는 계약** — [BACKEND.md](BACKEND.md) |
| 다국어 (한국어/영어) | 완료 | 모든 화면 문구가 `res/values/strings.xml` + `res/values-en/`로 이동. 판정 함수는 문구 대신 리소스 id를 돌려준다 — 유닛 테스트가 `Context`에 못 닿기 때문 (아래 "구현 메모") |
| 화이트리스트 (URL 예외) | **포팅 불가** | 앱 단위 감지라 URL 개념이 없음 |
| Shorts 항상 차단 / Shorts 별도 집계 | **포팅 불가** | `UsageStatsManager`는 패키지만 알고 화면 내용은 모름. AccessibilityService면 가능하나 위에서 배제한 방식 |
| Shorts 전용 일일 한도 | **포팅 불가** | 같은 이유. 서버 컬럼 `settings.shorts_limit_ms`는 존재하지만 안드로이드는 `SETTINGS_COLUMNS`에 넣지 않아 읽지도 쓰지도 않는다 — 확장이 넣은 값이 보존되게 |

### 구현 메모

- 폴링 주기는 적응형: 유튜브가 화면에 있으면 5초, 아니면 30초. 차단 반응성과 배터리를 맞바꾼 값.
- 판정 로직은 전부 순수 함수로 분리해 기기 없이 테스트함 (`limit/`, `gamification/`, `usage/DayWindow.kt`, `data/Encoding.kt`, `diagnostics/SyncDiagnostics.kt`, `sync/UsageMerge.kt`). 판정이 값을 반환하고 저장은 호출자가 하는 구조. 확장은 원래 `service-worker.js`가 DB 호출과 판정을 한 덩어리로 섞어놔서 유닛 테스트가 하나도 안 붙었는데, 지금은 이쪽 구조를 역으로 가져가 `lib/blockDecision.js` / `lib/alarmRules.js` / `lib/dateRollover.js`로 판정을 떼어냈다 — 두 클라이언트의 대응 파일이 이제 1:1로 붙는다.
- `UsageMonitorService.tick()`이 확장의 `handleTick()` 순서를 그대로 따름: 사용량 기록 → 날짜 롤오버 → 하드코어 쿨다운 → 긴급횟수 리셋 → 집중모드 승격/만료 → 긴급 만료 → 알림 → 차단 판정.
- DataStore에는 컬렉션을 넣기 어려워서 사용 기록·요일별 한도·뱃지는 직접 만든 문자열 인코딩으로 저장 (`data/Encoding.kt`). `org.json`은 유닛 테스트에서 스텁이라 못 쓰고, 필드 네 개 때문에 serialization 플러그인을 넣을 이유는 없다고 판단. 진단 로그 링버퍼도 같은 이유로 같은 방식(`U+001E`/`U+001F` 구분자)을 쓴다 — 새 의존성 없음.
- **화면 문구는 전부 `res/values/strings.xml`(한국어) + `res/values-en/`(영어)에 있다.** 문구를 추가할 땐 두 파일에 다 넣어야 하고, 두 로케일은 `app_name`만 다르다(앱 이름은 번역하지 않음).
  - **판정 함수는 문구 대신 리소스 id/enum을 돌려준다** (`diagnosticKindLabelRes`, `BlockReason.messageRes`, `CredentialError.asAuthMessage`, `staleSyncWarning`의 sealed `SyncWarning`). 유닛 테스트는 `Context`에 닿을 수 없어 안드로이드 리소스를 못 읽는데, 순수 함수가 문구를 만들어 돌려주면 그 함수가 통째로 테스트 밖으로 나간다. 문구를 붙이는 건 Compose 화면 몫.
  - 개수가 들어가는 문구는 문자열을 이어붙이지 말고 `plurals`를 쓴다 — 영어는 단수/복수가 갈리고 한국어는 안 갈린다.
  - 레이아웃 두 곳을 영어 기준으로 넓혔다(영어가 더 길다): 대시보드 스탯 행은 셀에 weight를 줬고("365 days" vs "365일"), 홈 모드 뱃지는 넘치는 대신 줄바꿈하게 바꿨다.
- 부팅 후 자동 시작(`BootReceiver`)은 감시 토글이 켜져 있을 때만.
- UI는 Material You 동적 색상 대신 확장과 같은 인디고 고정 팔레트(`ui/theme/Theme.kt`)로 맞춤 — 홈 화면(`HomeScreen.kt`)도 원형 진행 링·스트릭 히어로 카드·상태 뱃지로 확장 팝업과 같은 느낌으로 재구성, 집중 모드는 프리셋 대신 확장과 동일한 지연(분)+지속(분) 입력. 상시 알림도 브랜드 색 colorized + 진행바 적용.

### 라이브러리 선택

- `supabase-kt` 3.8.0 / Kotlin 2.4.20 / AGP 9.4.0 / Gradle 9.7.1. 정확한 목록은 `android/gradle/libs.versions.toml`.
- `auth-kt-android`가 multiplatform-settings를 같이 끌고 와서 세션 저장은 자동. 확장이 `chrome.storage` 어댑터를 직접 끼워넣어야 했던 것과 달리 추가 설정 없음.
- 이 의존성으로 디버그 APK가 12.7MB → 15.6MB로 늘어남. 이후 툴체인이 올라가면서 현재는 16.4MB(릴리스 APK는 R8 축소로 2.4MB).

### AGP 9에서 Kotlin 버전 올리는 법 (중요)

AGP 9는 Kotlin 컴파일러를 내장하고, 기본값은 2.2다. 이게 낮으면 최신 라이브러리가
`Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0`
으로 죽는다. 올리는 방법은 **KGP를 루트에 `apply false`로 클래스패스에만 올리는 것**:

```kotlin
// build.gradle.kts (root)
alias(libs.plugins.kotlin.android) apply false   // 적용하지 않음. 버전만 알려주는 용도
```

AGP가 클래스패스의 KGP 버전(`kotlinBaseApiPluginVersion`)을 읽어서 그 버전으로 내장 Kotlin을 돌린다. 모듈에는 적용하지 **말 것** — 적용하면 이렇게 죽는다:

> The 'org.jetbrains.kotlin.android' plugin is not compatible with AGP's 9.0 new DSL (`android.newDsl=true` is enabled by default).

에러 메시지가 안내하는 `android.builtInKotlin=false` / `android.newDsl=false`는 레거시 경로라 쓰지 않았다.

### 빌드 주의

- AGP 버전을 올린 뒤 첫 빌드가 `AAPT2 Daemon startup failed / Please check if you installed the Windows Universal C Runtime`로 죽으면 UCRT 문제가 아니라 옛 Gradle 데몬이 구 AAPT2를 물고 있는 것. `./gradlew --stop` 후 재빌드하면 해결됨.

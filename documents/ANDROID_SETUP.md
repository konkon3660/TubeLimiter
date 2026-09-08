# 안드로이드 앱 빌드/실행

`android/` 모듈 처음 열 때 필요한 최소 정보. 기능/권한/설계 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md), 백엔드 계약은 [BACKEND.md](BACKEND.md) 참고.

## 요구 사항

- Android Studio (AGP 9 계열 사용 중 — Kotlin 버전 올릴 때 주의사항은 MOBILE_PLAN.md 하단 참고).
- `compileSdk 37` / `minSdk 26` / `targetSdk 36`, JDK 17(= `compileOptions`의 컴파일 타깃. Gradle 데몬이 돌아가는 툴체인은 별개로 25에 고정돼 있다 — 아래 CI 절 참고).
- Supabase 연결 정보는 이미 코드에 박혀 있음(`app/src/main/java/com/tubelimiter/app/auth/SupabaseConfig.kt`) — 확장과 같은 프로젝트 공유라 별도 설정 불필요.

## 빌드

```
cd android
./gradlew assembleDebug
```

- 실기기 설치: `./gradlew installDebug` (USB 디버깅 켠 기기 연결 상태에서).
- 유닛 테스트(순수 함수 로직, 기기 불필요): `./gradlew testDebugUnitTest`.
- 코드 스타일: `./gradlew ktlintCheck` (빌드에 붙어 있음), 자동 교정은 `./gradlew ktlintFormat`. **`ignoreFailures = false`라 위반 하나에 빌드가 깨진다** — 처음엔 기존 위반 22건 때문에 `true`로 들여왔다가 backlog를 비운 뒤 뒤집었다. 스타일은 ktlint 기본값이 아니라 `intellij_idea`로 고정하고 `@Composable` 함수의 PascalCase는 예외로 뒀다(관례이지 위반이 아니라서). 설정은 `app/build.gradle.kts`의 `ktlint { }` 블록.
- 빌드가 `AAPT2 Daemon startup failed`로 죽으면 UCRT 문제 아님 — 옛 Gradle 데몬이 구버전 AAPT2를 물고 있는 것. `./gradlew --stop` 후 재시도.

### 버전

`versionCode`/`versionName`은 `android/gradle.properties`의 `tubelimiterVersionCode` / `tubelimiterVersionName`에 있다. 릴리스 때 여기 두 줄만 고치면 되고 빌드 스크립트는 안 건드린다. CI 등에서 일회성으로 덮어쓰려면 `-PtubelimiterVersionCode=... -PtubelimiterVersionName=...`.

### 릴리스 빌드와 서명

```
./gradlew assembleRelease
```

- **서명은 opt-in이다.** `android/keystore.properties`(gitignore 대상, **절대 커밋 금지**)에 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`를 넣어두면 릴리스 빌드가 그 키로 서명된다. 파일이 없으면 빌드는 그대로 성공하고 **미서명 APK**가 나온다 — CI나 갓 클론한 작업 트리가 비밀값 없이도 빌드되게 하려는 의도. `storeFile` 경로는 `android/` 기준이고, 그 경로에 파일이 없으면 서명 설정 자체가 안 만들어진다(역시 미서명으로 떨어짐).
- 릴리스는 R8 축소 + 리소스 축소가 켜져 있다. 동기화가 쓰는 kotlinx.serialization 모델은 리플렉션으로 잡히므로 `app/proguard-rules.pro`에 keep 룰이 있다 — **새 `@Serializable` 모델을 추가하면 여기도 확인할 것.** 안 그러면 디버그에선 되고 릴리스에서만 동기화가 깨진다.
- **ktor 엔진 keep 룰은 지우면 안 된다.** `createSupabaseClient`가 엔진을 명시하지 않고 `HttpClient()`를 만들어 `HttpClientEngineContainer`를 **ServiceLoader로** 찾는다. 룰이 없으면 R8이 컨테이너 클래스는 남기면서 정작 호출되는 `getFactory()`를 지워버려 **릴리스에서만 모든 네트워크가 죽는다**(실측 확인함: 룰을 빼고 빌드하면 `app/build/outputs/mapping/release/usage.txt`에 `OkHttpEngineContainer: getFactory()`가 제거 대상으로 찍힌다). 확인 방법은 `./gradlew assembleRelease` 후 `usage.txt`에 `EngineContainer` 항목이 **없고** `seeds.txt`에 `getFactory()`가 **있는지** 보는 것. ktor/supabase-kt 버전을 올릴 때마다 다시 확인할 것.
- `*.jks` / `*.p12` / `keystore.properties` / `release/`는 전부 `android/.gitignore`에 들어 있다.

### CI

`.github/workflows/ci.yml`의 `android` 잡이 push(main)와 PR마다 temurin JDK 25 + `platforms;android-37`을 깔고 세 게이트를 **각각 별도 스텝으로** 돌린다(같은 워크플로의 다른 잡이 확장 lint/format/test). SDK 경로는 러너가 내보내는 `ANDROID_HOME`에서 나온다 — `local.properties`는 커밋하지 않으므로.

1. `./gradlew ktlintCheck` — `ktlintCheck`는 `check`에 붙지 `test`에는 안 붙는다. 유닛 테스트만 돌리던 예전 설정에선 `ignoreFailures = false` 게이트가 CI에서 아예 안 돌고 로컬에만 존재했다.
2. `./gradlew testDebugUnitTest`
3. `./gradlew assembleRelease` — 릴리스에서만 R8 축소가 켜지므로 이 스텝이 없으면 `app/proguard-rules.pro`가 완전히 미검증이다(디버그 빌드는 minify를 안 한다). CI엔 `keystore.properties`가 없으니 미서명 APK로 그냥 성공한다 — 서명 비밀값 필요 없음.

한 스텝에 묶지 않은 건 어느 게이트가 깨졌는지 CI 로그에서 바로 보이게 하려는 것이고, 순서는 빠르고 잘 깨지는 것부터다(assembleRelease가 제일 느리다).

**JDK는 25지만 컴파일 타깃은 여전히 17이다** — 25는 `android/gradle/gradle-daemon-jvm.properties`의 `toolchainVersion=25`에 맞춘 데몬 툴체인 핀(러너가 foojay에서 JDK를 자동으로 받아오지 않게 하려는 것)이고, 모듈이 뱉는 바이트코드는 `compileOptions`의 Java 17 그대로다. 둘은 다른 값이니 한쪽만 보고 다른 쪽을 고치지 말 것.

**이 잡은 아직 한 번도 실행된 적이 없어 미검증이다** — SDK 채널에 `android-37`이 없거나 AGP 9가 JDK 21 툴체인을 요구하면 거기서 터진다. 새로 붙인 `ktlintCheck`/`assembleRelease` 스텝도 마찬가지로 CI에서 한 번도 안 돌아봤다. 첫 실행 결과를 보고 고쳐야 한다.

## 최초 실행 시 권한 온보딩

앱 실행하면 아래 4개를 순서대로 요구함(하나라도 빠지면 차단 기능 무력화):

1. 사용현황 접근 (`PACKAGE_USAGE_STATS`) — 설정 화면으로 이동해서 수동 허용.
2. 다른 앱 위에 표시 (`SYSTEM_ALERT_WINDOW`).
3. 배터리 최적화 제외.
4. 알림 권한 (`POST_NOTIFICATIONS`, Android 13+).

개인 서명 APK라 설치 시 "출처를 알 수 없는 앱" 경고가 뜰 수 있음 — 정상, 무시하고 진행. 상세 배경은 MOBILE_PLAN.md의 "개발 시 명심할 것" 참고.

## 모듈 구조

```
android/app/src/main/java/com/tubelimiter/app/
├── auth/          # Supabase 인증 (AuthRepository, SupabaseConfig, AuthMessages)
├── block/         # 차단 오버레이 (BlockOverlay)
├── data/          # 로컬 저장 (DataStore 래퍼 AppSettings/AppState, 커스텀 인코딩 Encoding.kt)
├── diagnostics/   # 동기화 진단 링버퍼 (SyncDiagnostics)
├── gamification/  # 스트릭/XP/레벨 순수 함수 (확장 lib/gamification.js 포팅)
├── limit/         # 한도/알람/차단/예약 판정 순수 함수 (BlockDecision, AlarmRules, ScheduleRules)
├── permission/    # 권한 상태 체크
├── service/       # UsageMonitorService(폴링 루프), BootReceiver
├── sync/          # Supabase 업서트/머지 (SyncRepository, RemoteModels, UsageMerge)
├── ui/            # Compose 화면 (Auth/Home/Onboarding/Settings/Dashboard)
└── usage/         # UsageStatsManager 폴링, 하루 경계 계산(DayWindow), 구간 합산(UsageFolding)

android/app/src/main/res/
├── values/        # strings.xml (한국어, default)
└── values-en/     # strings.xml (영어)
```

판정 로직(`limit/`, `gamification/`, `usage/DayWindow.kt`, `data/Encoding.kt`, `diagnostics/`, `sync/UsageMerge.kt`)은 전부 순수 함수 — 실기기 없이 `test/`에서 검증됨. 실기기가 필요한 건 `UsageStatsManager` 정확도와 오버레이 표시뿐.

## 문구 추가할 때 (i18n)

- 화면에 보이는 문구는 Kotlin에 인라인으로 쓰지 말고 `res/values/strings.xml`(한국어)과 `res/values-en/strings.xml`(영어) **양쪽에** 같은 이름으로 넣는다. 한쪽만 넣으면 그 로케일에서 문구가 한국어로 떨어지거나(en 누락) 빌드가 깨진다(ko 누락).
- 개수가 들어가는 문구는 문자열을 이어붙이지 말고 `plurals`를 쓴다.
- **순수 함수는 문구가 아니라 리소스 id나 enum을 돌려준다** (`BlockReason.messageRes()`, `diagnosticKindLabelRes()`, `CredentialError.asAuthMessage()`, `staleSyncWarning()`의 `SyncWarning`). 유닛 테스트는 `Context`가 없어 안드로이드 리소스를 못 읽으므로, 판정 함수가 문구를 만들어 돌려주는 순간 그 함수는 테스트할 수 없게 된다. 문구는 Compose 화면에서 붙인다. 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md)의 "구현 메모".
- 확장 쪽 대응 규칙은 [extension/README.md](../extension/README.md)의 "다국어(i18n)".

## 동기화 진단 로그

설정 화면의 접기 카드에서 최근 실패 50건과 마지막 성공 시각을 보고 복사/지우기 할 수 있다. 마지막 성공이 24시간을 넘으면 홈 화면에 한 줄 경고가 뜬다. 서버로는 안 올라가고 이 기기 DataStore에만 남는다.

**이벤트에 서버 오류 문구를 그대로 넣지 마라.** 허용 목록(`summarizeFailure`) + redact(`sanitizeDiagnosticCode`) 두 단계를 반드시 통과시켜야 하고, 이벤트 종류·버퍼 크기·24시간 임계값은 확장과 문자열까지 같아야 하는 계약이다 — [BACKEND.md](BACKEND.md)의 "동기화 진단 로그" 절.

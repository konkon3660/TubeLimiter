# 안드로이드 앱 빌드/실행

`android/` 모듈 처음 열 때 필요한 최소 정보. 기능/권한/설계 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md), 백엔드 계약은 [BACKEND.md](BACKEND.md) 참고.

## 요구 사항

- Android Studio (AGP 9.4.0 / Gradle wrapper 9.7.1 / Kotlin 2.4.20 — Kotlin 버전 올릴 때 주의사항은 MOBILE_PLAN.md 하단 참고).
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

`.github/workflows/ci.yml`의 `android` 잡이 push(main)와 PR마다 `android-actions/setup-android`로 `platform-tools` + `platforms;android-37.0` + `build-tools;37.0.0`을 깔고, temurin JDK 25를 얹은 뒤 세 게이트를 **각각 별도 스텝으로** 돌린다(같은 워크플로의 다른 잡이 확장 lint/format/test). SDK 경로는 러너가 내보내는 `ANDROID_HOME`에서 나온다 — `local.properties`는 커밋하지 않으므로.

1. `./gradlew ktlintCheck` — `ktlintCheck`는 `check`에 붙지 `test`에는 안 붙는다. 유닛 테스트만 돌리던 예전 설정에선 `ignoreFailures = false` 게이트가 CI에서 아예 안 돌고 로컬에만 존재했다.
2. `./gradlew testDebugUnitTest`
3. `./gradlew assembleRelease` — 릴리스에서만 R8 축소가 켜지므로 이 스텝이 없으면 `app/proguard-rules.pro`가 완전히 미검증이다(디버그 빌드는 minify를 안 한다). CI엔 `keystore.properties`가 없으니 미서명 APK로 그냥 성공한다 — 서명 비밀값 필요 없음.

한 스텝에 묶지 않은 건 어느 게이트가 깨졌는지 CI 로그에서 바로 보이게 하려는 것이고, 순서는 빠르고 잘 깨지는 것부터다(assembleRelease가 제일 느리다).

**JDK는 25지만 컴파일 타깃은 여전히 17이다** — 25는 `android/gradle/gradle-daemon-jvm.properties`의 `toolchainVersion=25`에 맞춘 데몬 툴체인 핀(러너가 foojay에서 JDK를 자동으로 받아오지 않게 하려는 것)이고, 모듈이 뱉는 바이트코드는 `compileOptions`의 Java 17 그대로다. 둘은 다른 값이니 한쪽만 보고 다른 쪽을 고치지 말 것.

**이 잡은 `main`에서 실제로 돌아 통과했다** — `ktlintCheck`/`testDebugUnitTest`/`assembleRelease` 세 스텝 모두. 다만 첫 실행은 두 번 깨졌고, 둘 다 SDK 설치 스텝이 원인이었다.

1. **손수 짠 sdkmanager 탐색이 JDK 25 아래에서 죽었다.** `find | tail` 프로브를 `set -euo pipefail`로 돌렸는데, 하필 직전 스텝이 JDK 25를 현재 Java로 만든 뒤였다 — cmdline-tools가 JDK 25를 지원한다는 보장이 없다. 패키지 가용성 문제가 아니었다. `android-actions/setup-android`로 교체하고 그 스텝을 **JDK 설치 앞**으로 옮겨 러너 기본 Java에서 돌게 했다. Gradle은 그다음 스텝에서 25를 받는다.
2. **`platforms;android-37`이라는 패키지는 존재하지 않는다.** sdkmanager가 `Failed to find package 'platforms;android-37'`로 죽었다. 플랫폼 패키지는 **마이너 버전제**로 바뀌어 `37.0`/`37.1`/`37.2`가 각각 별도 다운로드이고, `compileSdk = 37`은 `37.0`으로 해석된다(로컬 SDK에 깔려 있는 것도 `37.0`이다).

   ⚠️ **`compileSdk`를 올릴 때 또 걸릴 함정이다.** 워크플로의 `packages:` 줄을 같이 고치되 bare major(`platforms;android-38`)가 아니라 마이너까지 적어야 하고(`platforms;android-38.0`), `build-tools`도 같은 방식으로 짝을 맞춘다. 어느 마이너가 존재하는지는 `sdkmanager --list`로 확인할 것 — 없는 패키지를 적으면 빌드가 아니라 **SDK 설치 스텝에서** 죽는다.

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

판정 로직(`limit/`, `gamification/`, `usage/DayWindow.kt`, `data/Encoding.kt`, `diagnostics/`, `sync/UsageMerge.kt`)은 전부 순수 함수 — 실기기 없이 `test/`에서 검증됨.

**"실기기가 필요한 건 UsageStatsManager 정확도와 오버레이 표시뿐"이라고 적었던 건 낙관이었다.** `app/src/androidTest`는 **디렉터리 자체가 없어** 계측 테스트가 0개이고, 아래는 전부 실기기에서만 확인된다:

- `UsageStatsManager`가 보고하는 시간과 실제 시청 시간의 오차 (분할 화면 · 백그라운드 재생 · 화면 꺼짐 각각)
- 오버레이가 실제로 뜨는지, 그리고 그 위에서 긴급 시청이 눌리는지
- **포그라운드 서비스 생존** — 삼성·샤오미의 절전 정책이 며칠 방치된 서비스를 죽이는지(`diagnostics/MonitorHealth.kt`의 2시간 무심박 경고는 그때 뜨라고 만든 것이지, 서비스가 살아 있음을 보장하지 않는다)
- **권한 흐름** — 4종 온보딩, 그리고 사용자가 도중에 권한을 되돌렸을 때 `monitorWarning`이 실제로 뜨는지
- **부팅 복구** — `BootReceiver`가 재부팅 뒤 서비스를 되살리는지
- 강제 종료 후 감시가 다시 뜨기까지 걸리는 시간, 폴링 주기(활성 5초 / 유휴 30초) 때문에 오버레이가 늦는 정도

확인할 항목의 전체 목록은 [QA_REVIEW.md](QA_REVIEW.md) §8의 체크리스트다 — **아직 아무도 실행하지 않았다.** 하나씩 확인하면 결과를 이 문서와 [FEATURES.md](FEATURES.md)에 적는다.

## 문구 추가할 때 (i18n)

- 화면에 보이는 문구는 Kotlin에 인라인으로 쓰지 말고 `res/values/strings.xml`(한국어)과 `res/values-en/strings.xml`(영어) **양쪽에** 같은 이름으로 넣는다. 한쪽만 넣으면 그 로케일에서 문구가 한국어로 떨어지거나(en 누락) 빌드가 깨진다(ko 누락).
- 개수가 들어가는 문구는 문자열을 이어붙이지 말고 `plurals`를 쓴다.
- **순수 함수는 문구가 아니라 리소스 id나 enum을 돌려준다** (`BlockReason.messageRes()`, `diagnosticKindLabelRes()`, `CredentialError.asAuthMessage()`, `staleSyncWarning()`의 `SyncWarning`). 유닛 테스트는 `Context`가 없어 안드로이드 리소스를 못 읽으므로, 판정 함수가 문구를 만들어 돌려주는 순간 그 함수는 테스트할 수 없게 된다. 문구는 Compose 화면에서 붙인다. 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md)의 "구현 메모".
- 확장 쪽 대응 규칙은 [extension/README.md](../extension/README.md)의 "다국어(i18n)".

## 동기화 진단 로그

설정 화면의 접기 카드에서 최근 실패 50건과 마지막 성공 시각을 보고 복사/지우기 할 수 있다. 마지막 성공이 24시간을 넘으면 홈 화면에 한 줄 경고가 뜬다. 서버로는 안 올라가고 이 기기 DataStore에만 남는다.

**이벤트에 서버 오류 문구를 그대로 넣지 마라.** 허용 목록(`summarizeFailure`) + redact(`sanitizeDiagnosticCode`) 두 단계를 반드시 통과시켜야 하고, 이벤트 종류·버퍼 크기·24시간 임계값은 확장과 문자열까지 같아야 하는 계약이다 — [BACKEND.md](BACKEND.md)의 "동기화 진단 로그" 절.

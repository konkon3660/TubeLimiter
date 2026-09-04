# 안드로이드 앱 빌드/실행

`android/` 모듈 처음 열 때 필요한 최소 정보. 기능/권한/설계 배경은 [MOBILE_PLAN.md](MOBILE_PLAN.md), 백엔드 계약은 [BACKEND.md](BACKEND.md) 참고.

## 요구 사항

- Android Studio (AGP 9 계열 사용 중 — Kotlin 버전 올릴 때 주의사항은 MOBILE_PLAN.md 하단 참고).
- `compileSdk 37` / `minSdk 26` / `targetSdk 36`, JDK 17.
- Supabase 연결 정보는 이미 코드에 박혀 있음(`app/src/main/java/com/tubelimiter/app/auth/SupabaseConfig.kt`) — 확장과 같은 프로젝트 공유라 별도 설정 불필요.

## 빌드

```
cd android
./gradlew assembleDebug
```

- 실기기 설치: `./gradlew installDebug` (USB 디버깅 켠 기기 연결 상태에서).
- 유닛 테스트(순수 함수 로직, 기기 불필요): `./gradlew testDebugUnitTest`.
- 빌드가 `AAPT2 Daemon startup failed`로 죽으면 UCRT 문제 아님 — 옛 Gradle 데몬이 구버전 AAPT2를 물고 있는 것. `./gradlew --stop` 후 재시도.

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
├── auth/          # Supabase 인증 (AuthRepository, SupabaseConfig)
├── block/         # 차단 오버레이 (BlockOverlay)
├── data/          # 로컬 저장 (DataStore 래퍼 AppSettings/AppState, 커스텀 인코딩 Encoding.kt)
├── gamification/  # 스트릭/XP/레벨 순수 함수 (확장 lib/gamification.js 포팅)
├── limit/         # 한도/알람/차단 판정 순수 함수
├── permission/    # 권한 상태 체크
├── service/       # UsageMonitorService(폴링 루프), BootReceiver
├── sync/          # Supabase 업서트/머지 (SyncRepository, RemoteModels)
├── ui/            # Compose 화면 (Auth/Home/Onboarding/Settings/Dashboard)
└── usage/         # UsageStatsManager 폴링, 하루 경계 계산(DayWindow), 구간 합산(UsageFolding)
```

판정 로직(`limit/`, `gamification/`, `usage/DayWindow.kt`, `data/Encoding.kt`)은 전부 순수 함수 — 실기기 없이 `test/`에서 검증됨. 실기기가 필요한 건 `UsageStatsManager` 정확도와 오버레이 표시뿐.

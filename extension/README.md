# TubeLimiter v2 (확장 프로그램)

일일 시청 제한 + 차단은 유지하되, 그 위에 계정 로그인/서버 동기화와 자동 스트릭·XP·업적 게임화를 얹은 재작성 버전.
기존 대회 제출 버전은 `../reference/legacy-extension/`에 참고용으로 남아있음 (더 이상 수정 안 함).

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

### 4. 크롬에 로드
1. `chrome://extensions` → 개발자 모드 켜기.
2. "압축해제된 확장 프로그램을 로드합니다" → `extension/dist` 폴더 선택.
3. 툴바 아이콘 클릭 → "로그인/회원가입"으로 계정 생성 → 옵션에서 한도 설정.

## 구조

```
extension/
├── public/            # manifest.json, html, 정적 assets (그대로 dist/에 복사됨)
├── src/
│   ├── lib/           # storage, time, supabase client, 스트릭/XP 규칙, 한도 계산
│   ├── background/    # service worker: 탭 추적, 차단 판정, 자정 롤오버, Supabase 동기화
│   ├── content/       # 유튜브 페이지 차단 오버레이
│   ├── popup/         # 툴바 팝업 (오늘 사용량, 스트릭, 집중모드, 긴급시청)
│   ├── options/       # 설정 페이지 (한도/화이트리스트/Shorts/긴급시청)
│   ├── dashboard/     # 통계+스트릭 캘린더+업적 뱃지 (Chart.js)
│   └── auth/          # 로그인/회원가입 페이지
├── supabase/schema.sql
└── build.mjs          # esbuild 번들 스크립트
```

## 게임화 규칙

- **성공 판정**: 자정 롤오버 시 그날 총 사용시간이 그날의 한도 이내면 성공 (긴급 시청을 썼어도 총량이 한도 이내면 성공으로 인정).
- **스트릭**: 연속 성공일수. 실패하면 0으로 리셋(누적 성공일수/최고 기록은 유지).
- **XP**: 성공 1일당 10XP + 3/7/14/30/60/100/365일 스트릭 마일스톤 달성 시 보너스(마일스톤 일수 × 5).
- **레벨**: 삼각수 누적 공식으로 계산 (`src/lib/gamification.js` 의 `getLevelProgress`).
- **업적**: 스트릭 마일스톤 달성 시 자동 잠금 해제, 대시보드에서 뱃지로 표시.

## 알려진 제약

- `chrome.alarms`는 패키징된(스토어 배포) 확장에서 1분 미만 주기를 강제로 1분으로 올림 처리함 → 사용 시간 기록/차단 판정 해상도가 최대 약 1분. (탭 전환/URL 변경 시점에는 즉시 재계산되므로 체감 지연은 적음.)
- 스트릭 캘린더 히트맵은 과거 날짜에도 **현재 설정된 한도**를 소급 적용해서 성공/실패를 표시함(그 날 실제로 적용됐던 한도가 달랐어도 반영 못 함). 개인 동기부여용 근사치로 충분하다고 보고 단순화함.
- Android 앱은 이번 범위에 없음 — 별도 계획으로 진행 예정.

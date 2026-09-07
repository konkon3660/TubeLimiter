# 백엔드(Supabase) 계약

크롬 확장과 안드로이드 앱, 두 클라이언트가 같은 Supabase 프로젝트를 공유함 (계정도 기록도 하나). 스키마는 `extension/supabase/schema.sql`이 유일한 원본이고, 안드로이드는 마이그레이션 없이 그대로 붙여 씀. 이 문서는 두 클라이언트가 "같은 필드를 같은 의미로 쓰고 있는지" 확인용 — 한쪽만 고치면 깨지는 계약을 적어둠.

## 테이블

### `settings` (user_id PK, 1행/유저)

한도·화이트리스트·긴급시청·하드코어 모드 등 사용자 설정 전체. **서버가 진실의 원천** — 두 클라이언트 모두 "로그인 시 pull, 변경 시 즉시 push" 패턴.

| 컬럼 | 의미 | 비고 |
|---|---|---|
| `daily_limit_ms` | 기본 일일 한도 | 기본값 30분 |
| `daily_limit_by_day` | 요일별 한도 오버라이드 (jsonb) | 요일 인덱스 일=0 기준 — 확장/안드로이드 동일하게 맞춰야 함 |
| `daily_limit_reset_frequency` | 한도 리셋 주기 | |
| `always_block_shorts` | Shorts 항상 차단 | **안드로이드는 지원 불가** (앱 단위 감지라 화면 내용 모름) — 값은 동기화되지만 안드로이드에서 무시됨 |
| `whitelist` | URL 화이트리스트 (jsonb) | **안드로이드는 지원 불가** (URL 개념 없음, 앱 단위) |
| `emergency_config` | 긴급 시청 5분 허용 횟수/리셋 주기 | |
| `alarm_interval_minutes` / `alarm_milestones_enabled` | 사용시간 알림 | |
| `hardcore_mode` / `hardcore_disable_requested_at` | 하드코어 모드, 끄기 요청 시각 | 끄기는 1시간 쿨다운 후 실제 반영 — 로직은 각 클라이언트가 로컬에서 판정, 이 컬럼은 그 판정에 필요한 상태만 저장 |
| `scheduled_blocks` | 예약 차단(요일별 반복 시간대 자동 차단), jsonb 배열 | 원소 형태: `{id, label, days[7], startMinute, endMinute, enabled}`. `days`는 `daily_limit_by_day`와 같은 컨벤션(일=0 기준). `startMinute`/`endMinute`는 자정 기준 분(0~1439), `"HH:mm"` 문자열이 아님. **wrap 규칙**: `endMinute <= startMinute`면 자정을 넘기는 구간이고, 그 구간은 `days`가 가리키는 "시작 요일"에 속한다(종료 요일 쪽 `days`는 무관) — 예: `days`에 월요일만 켜져 있고 22:00~07:00이면 월요일 22시~화요일 07시에 활성, 화요일 밤엔 관여 안 함. **4시 사용량 컷오프와는 무관** — 실제 벽시계 요일/시각(확장 `Date#getDay()`, 안드로이드 `LocalDate.now().dayOfWeek`)으로만 판정하고 `getTodayDate()`/`DAY_CUTOFF_HOUR`를 쓰지 않는다. 판정 순수 함수: 확장 `lib/schedule.js`, 안드로이드 `limit/ScheduleRules.kt` — 하나 고치면 다른 쪽도 맞춰야 함. 안드로이드도 지원(브라우저 전용인 `whitelist`/`always_block_shorts`와 달리 시간대 커퓨는 네이티브 앱에도 적용됨). |

`whitelist`/`always_block_shorts`는 안드로이드에서 의미 없는 값이지만 그렇다고 안드로이드가 이 필드를 비우거나 덮어쓰면 안 됨. 안드로이드는 **이 두 컬럼을 select에도 upsert payload에도 아예 넣지 않는 방식**으로 지킨다 — PostgREST의 upsert는 요청 본문에 있는 키에 대해서만 `ON CONFLICT DO UPDATE SET`을 만들기 때문에, 빠진 컬럼은 기존 값이 그대로 남는다. (원격 값을 pull 해서 그대로 되돌려 쓰는 왕복 방식도 결과는 같지만, pull과 push 사이에 확장이 값을 바꾸면 낡은 값으로 덮어쓰게 되므로 택하지 않았다.) 컬럼 목록은 `sync/RemoteModels.kt`의 `SETTINGS_COLUMNS`, 검증은 `RemoteModelsTest`의 "the payload carries only the columns this client owns".

### `daily_usage` (user_id + date 복합 PK)

그날 누적 사용시간(`usage_ms`), Shorts 시간(`shorts_ms`), 그중 긴급 시청으로 본 시간(`emergency_ms`). **합산 방식으로 해결됨** — 두 클라이언트가 같은 행에 절대값을 upsert하면 나중에 쓴 기기가 먼저 쓴 기기 몫을 지워버리므로(예: 확장 40분 + 폰 10분 upsert 순서에 따라 그날 기록이 10분이 되는 문제), 절대값 upsert 대신 `increment_daily_usage(p_date, p_usage_delta_ms, p_shorts_delta_ms, p_emergency_delta_ms)` RPC로 "지난 동기화 이후 늘어난 만큼"만 델타로 보내고 서버가 누적한다. `p_emergency_delta_ms`는 기본값이 0이라 인자 3개로 부르는 기존 클라이언트(안드로이드)도 그대로 동작한다 — 대신 이름이 같은 함수가 둘이 되면 3인자 호출이 모호해지므로 스키마는 옛 3인자 버전을 `drop` 하고 4인자 버전 하나만 남긴다.

- 함수 정의는 `extension/supabase/schema.sql` 하단 참고. `security invoker`(기본값)라 `auth.uid()`가 호출자 세션 그대로 evaluate되고 `daily_usage`의 RLS가 그대로 적용됨.
- 반환값(`usage_ms`, `shorts_ms`, `emergency_ms`)은 그 행의 최신 합계 — 이 기기만이 아니라 계정을 공유하는 다른 기기 몫까지 포함됨. 호출자는 이 합계에서 자기가 이미 보고한 몫을 빼 "다른 기기 몫"만 자기 로컬 최신값 위에 더해서 한도 판정에 쓴다 (합치는 계산은 순수 함수로 양쪽에 포팅됨: 확장 `lib/usageMerge.js`, 안드로이드 `sync/UsageMerge.kt` — 하나 고치면 다른 쪽도 맞춰야 함).
- **확장**: `service-worker.js`의 `syncUsageToSupabase`가 30초 스로틀로 델타를 보내고, `getEffectiveTodayUsage`가 합계를 로컬 사용량 판정에 반영.
- **안드로이드**: `UsageMonitorService.refreshDailyUsageSync`가 같은 패턴(30초 스로틀)으로 사용시간과 긴급 시청 시간 델타를 보내고, `combinedUsedMillis`로 접은 값을 차단 판정(`effectiveUsedMillis`)에 반영. Shorts는 안드로이드가 화면 내용을 모르므로 항상 델타 0으로 보냄.
- 어느 한쪽이 오프라인이거나 로그인 전이면 그 기기 판정은 로컬 값만으로 계속 동작 — 합산은 로그인 + 동기화 성공 시에만 반영되는 보너스 정보.

### `streaks` (user_id PK, 1행/유저)

`current_streak`, `best_streak`, `last_result_date`, `total_success_days`, `xp`, 그리고 완벽한 날(긴급 시청 0회) 집계인 `perfect_days`, `current_perfect_streak`, `best_perfect_streak`.

- 긴급 시청을 쓴 날은 `current_streak`은 이어지고 완벽한 날 3개 값만 끊긴다 (판정 규칙은 확장 `gamification.js`의 `isDaySuccess`/`isPerfectDay`와 안드로이드 `Gamification.kt`의 동명 함수 — 양쪽 동일 규칙, 한쪽 고치면 다른 쪽도 맞춰야 함. [FEATURES.md](FEATURES.md) 게임화 규칙 참고).
- 긴급 시청 시간/횟수는 날짜별로 로컬에만 남는다 (확장 `emergency_history`, 안드로이드 `emergency_history`/`emergency_uses_history` DataStore 키). 서버로는 시간만 `daily_usage.emergency_ms`로 합산되고 횟수는 안 올라가므로, 롤오버 판정은 그 날을 실제로 시청한 기기의 로컬 기록으로 이뤄진다.

- 자정(4시 컷오프 기준) 롤오버 때 그날 성공/실패 판정 후 갱신, 두 클라이언트 모두 구현됨(안드로이드 `SyncRepository.pushStreak`, 확장 `gamification.js`).
- 병합 규칙: 각 클라이언트는 로그인 직후 pull 해서 로컬보다 서버 기록이 "더 진행된" 경우 그걸 채택(`SyncRepository.mergeStreaks` 참고) — 두 기기를 오가며 써도 기록이 뒤로 가지 않게.
- 하드코어 모드를 끄면 `current_streak`을 0으로 리셋(확장은 `service-worker.js`에서 직접 update, 안드로이드도 동일 정책 — [FEATURES.md](FEATURES.md) 게임화 규칙 참고).

### `achievements` (user_id + key 복합 PK)

스트릭 마일스톤 뱃지(`streak_N`)와 완벽한 날 연속 마일스톤 뱃지(`perfect_N`). `upsert(..., { onConflict: 'user_id,key', ignoreDuplicates: true })` 패턴으로 중복 잠금해제 무시 — 두 클라이언트 동일.

## RLS

네 테이블 모두 `auth.uid() = user_id`인 행만 읽기/쓰기 가능 (`for all using ... with check ...`). 서비스 키 없이 anon key + 로그인 세션만으로 각자 자기 행만 건드릴 수 있음 — 그래서 anon key는 코드에 커밋해도 안전함 (`extension/src/lib/config.js`, `android/.../SupabaseConfig.kt`).

## Edge Function: 계정 삭제 (`delete-account`)

구글 플레이는 앱 안에 계정 삭제 경로가 있을 것을 요구한다. 그런데 위 RLS 구조는 `public` 스키마 테이블만 지켜주는 것이고, 계정 자체(`auth.users` 행)는 anon key로 건드릴 수 없다 — `service_role` 키가 있어야 한다. 그 키는 클라이언트에 넣으면 RLS를 통째로 무력화하는 마스터 키가 되므로, 서버에서만 도는 Edge Function으로 감싼다. 소스는 `extension/supabase/functions/delete-account/index.ts`.

### 동작

1. JWT 검증은 **켜둔 채로**(배포 기본값) 쓴다. 호출자 신원은 `Authorization: Bearer <access token>` 헤더에서만 나온다.
2. 그 헤더를 얹은 anon 클라이언트로 `getUser()`를 불러 토큰의 주인을 확인한다. 실패하면 401.
3. 그 다음에야 `SUPABASE_SERVICE_ROLE_KEY`로 admin 클라이언트를 만들어 `auth.admin.deleteUser(user.id)`를 호출한다.

**요청 본문은 읽지 않는다.** 지울 대상 id는 오직 `getUser()`에서만 나온다 — 본문의 `user_id` 같은 값을 받는 순간 "남의 id를 넣어 남의 계정을 지우는" 구멍이 되기 때문. 호출자는 자기가 가진 토큰의 주인 외에 다른 id를 만들어낼 수 없으므로, 자기 계정만 지울 수 있다는 보장이 여기서 나온다.

`POST`(와 프리플라이트 `OPTIONS`)만 받고 나머지 메서드는 405 — 되돌릴 수 없는 작업이라 GET 프리페치 같은 걸로 실수로 불리면 안 되기 때문. 응답은 성공/실패 모두 `{ success, error? }` 형태의 JSON이고, admin 호출이 실패해도 원문 에러는 서버 로그에만 남기고 클라이언트에는 `delete_failed` 같은 일반 코드만 준다(내부 테이블/제약조건 이름이 새 나가지 않게).

CORS는 확장이 `chrome-extension://` 오리진에서 부르기 때문에 필요하다(확장 ID마다 오리진이 달라 `Access-Control-Allow-Origin: *`, 함수 자체는 JWT로 스스로를 지킨다). 안드로이드는 네이티브 HTTP라 CORS와 무관하지만 같은 핸들러가 양쪽을 받는다.

### 네 테이블은 알아서 지워진다

`daily_usage` / `settings` / `streaks` / `achievements` 네 개 모두 `user_id`가 `references auth.users(id) on delete cascade`다. 그래서 auth 유저 한 줄만 지우면 DB가 나머지를 같이 지운다 — **함수는 테이블별 delete를 하지 않는다.** 여기서 또 지우면 같은 규칙이 스키마와 함수 두 군데로 갈라져서, 나중에 테이블이 하나 늘 때 함수를 같이 안 고치면 조용히 찌꺼기가 남는다. 테이블을 추가할 때 cascade만 제대로 걸면 이 함수는 건드릴 필요가 없다(아래 "스키마 바꿀 때 체크리스트" 참고).

### 배포

Supabase CLI가 `supabase/functions/<이름>`을 **작업 디렉터리 기준으로** 찾으므로 `extension/`에서 실행한다.

```bash
cd extension
supabase functions deploy delete-account --project-ref gigudjceurfcxcnuhlph
```

`SUPABASE_URL` / `SUPABASE_ANON_KEY` / `SUPABASE_SERVICE_ROLE_KEY`는 배포된 함수에 Supabase가 **자동으로 주입**한다. `supabase secrets set`으로 따로 넣을 필요 없고, 무엇보다 **service_role 키는 레포에도 클라이언트(확장 `config.js`, 안드로이드 `SupabaseConfig.kt`)에도 절대 들어가면 안 된다** — anon key와 달리 RLS를 전부 무시하는 키라서, 커밋되는 순간 모든 사용자의 모든 행이 열린다. 함수 코드도 키 값을 로그에 찍지 않는다.

### 호출 방법

확장(supabase-js) — 로그인 세션의 access token이 자동으로 실려 나간다:

```js
const { data, error } = await supabase.functions.invoke('delete-account');
// 성공하면 로컬 세션/캐시를 지우고 signOut()
```

안드로이드(supabase-kt):

```kotlin
val response = supabase.functions.invoke("delete-account")
```

응답을 받은 뒤 **클라이언트가 로컬 세션과 로컬 기록을 직접 지워야 한다.** 이미 발급된 access token은 만료 전까지 형식상 유효하기 때문(접근할 행이 cascade로 다 사라져 실제로 할 수 있는 일은 없다). 삭제 후에는 같은 구글 계정으로 다시 로그인하면 새 `user_id`의 빈 계정이 만들어진다.

## 하루 경계(4시 컷오프)

"하루"는 자정이 아니라 **오전 4시** 기준으로 나뉨(늦게까지 보다 자는 경우 전날 사용량으로 집계). 확장은 `lib/time.js`의 `DAY_CUTOFF_HOUR`, 안드로이드는 `usage/DayWindow.kt`가 각각 구현 — 로직은 포팅됐고 순수 함수라 유닛 테스트로 검증됨. 이 값을 바꾸려면 **양쪽 다** 고쳐야 함.

## 스키마 바꿀 때 체크리스트

1. `extension/supabase/schema.sql`에 `alter table ... add column if not exists ...`로 추가(기존 설치 깨지지 않게, 파일 상단 하드코어 모드 컬럼 추가 사례 참고).
2. 확장 쪽 upsert/select 컬럼 목록 업데이트.
3. 안드로이드 `sync/RemoteModels.kt`의 직렬화 모델에 필드 추가.
4. 이 문서의 표 갱신.

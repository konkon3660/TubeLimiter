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
| `shorts_limit_ms` | Shorts 전용 일일 한도 | `0` = 미설정 = 한도 없음 (`daily_limit_ms`의 0 컨벤션과 동일). 요일별 오버라이드는 **없다** — 값 하나가 모든 요일에 적용된다. **브라우저 전용**, `always_block_shorts`/`whitelist`와 같은 취급. 판정은 확장 `lib/limits.js`의 `computeShortsLimit`(0·null·음수를 전부 Infinity로 접음 — 음수 한도는 "Shorts 영구 차단"이 되어버린다) |
| `whitelist` | URL 화이트리스트 (jsonb) | **안드로이드는 지원 불가** (URL 개념 없음, 앱 단위). **값의 형식이 계약이다** — 항목은 호스트 정확일치 / 호스트+경로 접두사 / 채널·영상 토큰 셋 중 하나로만 해석되고(확장 `lib/whitelist.js`의 `parseWhitelistEntry`), 유튜브 본체 호스트나 `/`·`/watch` 같은 전면 통과 값은 저장이 거부된다. **해석 못 하는 항목은 판정에서 무시**한다 — 검증이 없던 시절의 값이나 옛 빌드가 동기화해 넣은 값이 이 컬럼에 이미 들어 있을 수 있어서, 저장 쪽만 막으면 그 값들이 계속 산다. 옛 항목을 지우지 않고 무시만 하는 건 의도적이다(옵션 화면이 무효 항목과 사유를 보여준다). `music.youtube.com`은 일부러 허용 목록에 남겨뒀다 |
| `emergency_config` | 긴급 시청 5분 허용 횟수/리셋 주기 | |
| `alarm_interval_minutes` / `alarm_milestones_enabled` | 사용시간 알림 | 발화 판정은 양쪽 순수 함수(`lib/alarmRules.js`의 `evaluateAlarms` ↔ `limit/AlarmRules.kt`)가 같은 규칙으로 한다 — N분 주기, 남은 30·10·5·1분 마일스톤, 그리고 **예약 차단 시작 10분 전 예고**(`SCHEDULE_SOON_LEAD_MINUTES`). 이 상수와 판정 위치를 한쪽에서만 옮기면 한 기기만 예고를 띄운다 |
| `hardcore_mode` / `hardcore_disable_requested_at` | 하드코어 모드, 끄기 요청 시각 | 끄기는 1시간 쿨다운 후 실제 반영 — 로직은 각 클라이언트가 로컬에서 판정, 이 컬럼은 그 판정에 필요한 상태만 저장. **`hardcore_mode`가 true인 동안 이 행에 무엇을 쓸 수 있는지가 계약이다** — 아래 "하드코어 잠금 범위" 절 |
| `scheduled_blocks` | 예약 차단(요일별 반복 시간대 자동 차단), jsonb 배열 | 원소 형태: `{id, label, days[7], startMinute, endMinute, enabled}`. `days`는 `daily_limit_by_day`와 같은 컨벤션(일=0 기준). `startMinute`/`endMinute`는 자정 기준 분(0~1439), `"HH:mm"` 문자열이 아님. **wrap 규칙**: `endMinute <= startMinute`면 자정을 넘기는 구간이고, 그 구간은 `days`가 가리키는 "시작 요일"에 속한다(종료 요일 쪽 `days`는 무관) — 예: `days`에 월요일만 켜져 있고 22:00~07:00이면 월요일 22시~화요일 07시에 활성, 화요일 밤엔 관여 안 함. **4시 사용량 컷오프와는 무관** — 실제 벽시계 요일/시각(확장 `Date#getDay()`, 안드로이드 `LocalDate.now().dayOfWeek`)으로만 판정하고 `getTodayDate()`/`DAY_CUTOFF_HOUR`를 쓰지 않는다. 판정 순수 함수: 확장 `lib/schedule.js`, 안드로이드 `limit/ScheduleRules.kt` — 하나 고치면 다른 쪽도 맞춰야 함. 안드로이드도 지원(브라우저 전용인 `whitelist`/`always_block_shorts`와 달리 시간대 커퓨는 네이티브 앱에도 적용됨). |

`whitelist`/`always_block_shorts`/`shorts_limit_ms`는 안드로이드에서 의미 없는 값이지만 그렇다고 안드로이드가 이 필드를 비우거나 덮어쓰면 안 됨. 안드로이드는 **이 세 컬럼을 select에도 upsert payload에도 아예 넣지 않는 방식**으로 지킨다 — PostgREST의 upsert는 요청 본문에 있는 키에 대해서만 `ON CONFLICT DO UPDATE SET`을 만들기 때문에, 빠진 컬럼은 기존 값이 그대로 남는다. (원격 값을 pull 해서 그대로 되돌려 쓰는 왕복 방식도 결과는 같지만, pull과 push 사이에 확장이 값을 바꾸면 낡은 값으로 덮어쓰게 되므로 택하지 않았다.) 컬럼 목록은 `sync/RemoteModels.kt`의 `SETTINGS_COLUMNS`, 검증은 `RemoteModelsTest`의 "the payload carries only the columns this client owns".

#### 하드코어 잠금 범위 — `hardcore_mode`가 켜져 있을 때 이 행에 무엇을 쓸 수 있나

**잠금의 정의는 "한도 입력을 disabled로 만든다"가 아니라 "차단을 약화시키는 저장을 거부한다"다.** 화면에서 가리는 것과 규칙으로 막는 것은 다르고, 계약은 후자다 — 낡은 옵션 탭, 다른 기기, 오프라인 저장 경로 어디서 와도 같은 판정을 거쳐야 한다. 판정은 저장 직전에 **이전 값과 저장하려는 값을 비교**해서 한다(확장 `lib/hardcoreLock.js`의 `findHardcoreViolations`).

**거부되는 방향(약화):**

| 컬럼 | 거부 조건 |
|---|---|
| `daily_limit_ms` | 커지는 것. 0/미설정은 무제한이라 가장 느슨한 값으로 접어서 비교 |
| `daily_limit_by_day` | 요일 하나라도 커지는 것. 요일별 오버라이드를 **지우는 것**도 그 요일이 기본 한도로 풀리는 것이므로 기본 한도와 비교 |
| `shorts_limit_ms` | 커지는 것 |
| `whitelist` | 항목 **추가**(삭제는 허용 — 차단이 세지는 방향) |
| `emergency_config` | `dailyUses` 증가, 그리고 리셋 주기를 **짧게** 바꾸는 것(`monthly` < `weekly` < `daily` 순으로 느슨해진다) |
| `always_block_shorts` | true → false |
| `scheduled_blocks` | id 기준으로 사라짐 · `enabled: false` · 막는 분량 축소(요일 수 × 구간 길이, 자정 넘는 wrap은 펴서 계산) |

**허용되는 방향(강화):** 한도 축소, 예약 차단 추가·확대, 화이트리스트 삭제. 하드코어 중에 규칙을 더 세게 만드는 것까지 막으면, 스스로를 더 옥죄려는 사용자가 하드코어를 끄고(1시간 대기 + 스트릭 리셋) 다시 켜야 한다 — 커밋먼트 장치가 커밋먼트를 방해하는 셈이다. 애매한 값은 **약화로 본다**(막는다): 잘못 막으면 1시간 기다렸다 바꾸면 그만이지만, 잘못 열어주면 그게 곧 우회로다.

`hardcore_mode` 자체를 false로 바꾸는 것은 여기서 막지 않는다 — 그건 1시간 쿨다운(`lib/hardcore.js`)이 담당하는 **별개 관문**이고, 여기서 또 막으면 해제 요청조차 저장할 수 없게 된다.

**지금 이 계약을 지키는 건 확장뿐이다.** 안드로이드 `ui/SettingsScreen.kt`는 `limitsLocked = settings.hardcoreMode`로 **한도 입력(일일/요일별, 모드 선택 칩)만** `enabled = false` 처리하고, 긴급 시청 허용 횟수·리셋 주기·예약 차단은 하드코어 중에도 그대로 편집된다. 즉 **같은 계정에서 폰으로는 되고 PC로는 안 되는 상태**이며, 폰에서 올린 값은 서버를 거쳐 PC의 캐시로도 들어온다(확장은 다음 저장 때부터 그 값을 새 기준선으로 삼는다). 안드로이드에 같은 규칙을 포팅하기 전까지는 이 격차가 남는다.

#### 오프라인 편집 대기분 (`pendingSettingsSync`, 확장 로컬)

서버 테이블이 아니라 클라이언트 로컬 키지만, **서버 `settings` 행에 언제 무엇이 쓰이는지를 바꾸는** 규칙이라 여기 적는다(확장 `lib/offlineSettings.js`).

1. **"로그아웃"과 "서버에 못 닿음"을 구분한다.** 로컬 세션이 없으면 로그아웃. 세션은 있는데 `getUser()`가 실패했으면 오프라인. 단 서버가 **답을 한** 경우(400/401/403/404/409/422, 또는 `PGRST`로 시작하는 코드)는 세션이 죽은 것이므로 로그아웃 취급이다 — 여기를 뭉뚱그리면 토큰이 취소된 계정이 영원히 오프라인 편집 상태로 남는다. 5xx와 408/425/429, 상태 코드가 아예 없는 실패(pause된 프로젝트·DNS 실패)는 오프라인.
2. **오프라인 저장은 `settingsCache` + 대기분에 함께 쓴다.** 차단 판정은 로컬 캐시를 보므로 새 값이 즉시 걸린다.
3. **대기분은 누적 패치다.** 여러 번 저장하면 필드가 합쳐진다(매번 통째로 덮어쓰면 앞선 저장에서만 건드린 항목이 사라진다). 계정이 다르면 앞의 대기분은 버린다 — A의 오프라인 편집이 B의 계정으로 올라가면 안 된다.
4. **서버가 살아나면 대기분 push가 pull보다 우선한다.** 기본 계약("settings는 서버가 진실의 원천")을 이 한 줄만 뒤집는다 — 대기분은 사용자가 방금 명시적으로 한 변경이라 서버 값으로 덮으면 "저장했는데 되돌아왔다"가 된다. 대기분이 없으면 기존대로 pull.
5. **push는 대기분에 든 필드만 올린다.** 오프라인이던 시절의 캐시를 통째로 올리면 그 사이 다른 기기가 바꾼 항목까지 옛 값으로 되돌린다. **그래도 마지막 쓰기가 이긴다** — 행에 버전도 벡터 시계도 없어서 같은 필드를 다른 기기가 바꿨는지 알 방법이 없다. 해결하려면 필드 단위 타임스탬프가 필요하고, 지금은 하지 않는다.
6. **하드코어 관문은 온라인/오프라인 분기 전에 탄다**(`planSettingsSave`). 안 그러면 "인터넷을 끊고 옵션을 열어 한도를 올린다"는 새 우회로를 만드는 셈이다. 하드코어 해제 **요청**은 오프라인에서도 성립한다 — 1시간 쿨다운은 원래부터 로컬 판정이다.
7. 계정 삭제는 대기분 키도 지운다(`ACCOUNT_DELETE_REMOVED_KEYS`). 올릴 계정이 사라졌기 때문.

**안드로이드에는 이 경로가 없다.** 확장만의 동작이다.

### `daily_usage` (user_id + date 복합 PK)

그날 누적 사용시간(`usage_ms`), Shorts 시간(`shorts_ms`), 그중 긴급 시청으로 본 시간(`emergency_ms`), 그날 부여받은 긴급 시청 횟수(`emergency_uses`). **합산 방식으로 해결됨** — 두 클라이언트가 같은 행에 절대값을 upsert하면 나중에 쓴 기기가 먼저 쓴 기기 몫을 지워버리므로(예: 확장 40분 + 폰 10분 upsert 순서에 따라 그날 기록이 10분이 되는 문제), 절대값 upsert 대신 `increment_daily_usage(p_date, p_usage_delta_ms, p_shorts_delta_ms, p_emergency_delta_ms, p_emergency_uses_delta)` RPC로 "지난 동기화 이후 늘어난 만큼"만 델타로 보내고 서버가 누적한다.

- 함수 정의는 `extension/supabase/schema.sql` 하단 참고. `security invoker`(기본값)라 `auth.uid()`가 호출자 세션 그대로 evaluate되고 `daily_usage`의 RLS가 그대로 적용됨.
- **시그니처는 5인자 하나뿐이다.** 옛 3인자·4인자 버전은 스키마가 `drop` 한다: 이름이 같은 함수가 여럿 남으면 인자를 적게 넘기는 호출이 `function is not unique`로 실패하고, 5인자 버전은 반환 테이블에 `emergency_uses`가 붙어 반환 타입이 달라졌으므로 `create or replace`로 갈아끼울 수도 없다("cannot change return type of existing function"). drop 하면 `execute` 권한도 같이 사라지므로 `revoke ... from public` / `grant ... to authenticated`를 새 시그니처에 다시 걸어야 한다.
- **두 클라이언트가 같은 변경에서 같이 올라가야 한다.** 옛 빌드가 남아 있으면 그 기기의 사용량 동기화가 통째로 실패한다(진단 로그에 `sync_usage` 실패로 쌓인다).
- 반환값(`usage_ms`, `shorts_ms`, `emergency_ms`, `emergency_uses`)은 그 행의 최신 합계 — 이 기기만이 아니라 계정을 공유하는 다른 기기 몫까지 포함됨. 호출자는 이 합계에서 자기가 이미 보고한 몫을 빼 "다른 기기 몫"만 자기 로컬 최신값 위에 더해서 한도 판정에 쓴다 (합치는 계산은 순수 함수로 양쪽에 포팅됨: 확장 `lib/usageMerge.js`, 안드로이드 `sync/UsageMerge.kt` — 하나 고치면 다른 쪽도 맞춰야 함).
- **확장**: `service-worker.js`의 `syncUsageToSupabase`가 30초 스로틀로 델타를 보내고, `getEffectiveTodayUsage`가 합계를 로컬 사용량 판정에 반영. Shorts 합계도 사용시간과 같은 방식으로 캐시해 판정에 쓴다 — 처음엔 이걸 빠뜨려서 PC 두 대가 각각 Shorts 예산을 통째로 썼다.
- **안드로이드**: `UsageMonitorService.refreshDailyUsageSync`가 같은 패턴(30초 스로틀)으로 사용시간·긴급 시청 시간·긴급 횟수 델타를 보내고, `combinedUsedMillis`로 접은 값을 차단 판정(`effectiveUsedMillis`)에 반영. Shorts는 안드로이드가 화면 내용을 모르므로 항상 델타 0으로 보냄.
- 어느 한쪽이 오프라인이거나 로그인 전이면 그 기기 판정은 로컬 값만으로 계속 동작 — 합산은 로그인 + 동기화 성공 시에만 반영되는 보너스 정보.
- 대시보드가 그리는 **날짜별 과거 기록**도 이 테이블에서 읽어 로컬과 합친다(확장 `lib/historyMerge.js`). 합치는 규칙은 오늘치와 달리 날짜별 `max(로컬, 서버)`다 — 서버 합계에 이 기기 몫이 이미 들어 있어 더하면 이중 계산이고, 30초 스로틀 때문에 로컬이 앞서 있을 수 있어 서버를 무조건 채택할 수도 없다. 지난 날짜는 기기별 보고분을 되짚을 근거가 남아 있지 않아 "지금까지 알려진 최대"를 택한다.

#### `usage_ms`가 세는 물리량은 두 클라이언트가 서로 다르다

델타 합산 계약은 "숫자를 어떻게 더하는가"만 정한다. **그 숫자가 무엇을 잰 값인지는 플랫폼마다 다르고, 그 차이가 같은 행에 그대로 섞인다.**

| | 시간이 깎이는 조건 | 안 깎이는 것 |
|---|---|---|
| **확장** | 재생 중 **and** 탭 보임(`document.visibilityState`) **and** 크롬 창 포커스 | PiP, 백그라운드 오디오, 다른 창에서 작업하며 옆에 틀어놓기, 피드·검색·댓글(집계는 `/watch`·`/shorts`만) |
| **안드로이드** | 감시 대상 앱이 foreground (재생 여부와 무관) | 화면을 끈 백그라운드 재생, 폰 브라우저의 `m.youtube.com`(`UsageStatsManager`는 URL을 못 본다) |

따라서 **같은 계정의 두 기기가 같은 한도에 서로 다른 물리량을 더한다.** 이건 버그가 아니라 각 플랫폼이 볼 수 있는 것의 차이이고, 지금은 정의를 맞추는 대신 **문서에 적어두기로 한 결정**이다. 판정 규칙을 고칠 때 이 비대칭을 없앤 셈 치면 안 된다 — 특히 "잠수 방지"를 안드로이드에 넣거나 확장에서 포커스 조건을 빼는 변경은 **같은 사용자의 하루 사용량 총합을 바꾸므로** 양쪽을 같이 결정해야 한다. 각 조건의 근거 코드와 대가는 [FEATURES.md](FEATURES.md)의 "측정의 정의".

부수 계약 둘:

- **Shorts 델타는 안드로이드가 항상 0이다.** 화면 내용을 모르므로 구분할 수 없다. 확장만 `shorts_ms`를 올린다.
- **YouTube Music의 기본값은 양쪽이 같아야 한다.** 확장은 `music.youtube.com`이 `/watch`라 기본으로 영상 한도를 깎고 화이트리스트로 뺄 수 있으며, 안드로이드는 반대로 감시 대상에서 **기본 제외**이고 설정에서 켠다(`usage/WatchedPackages.kt`). 방향은 다르지만 결론("음악은 사용자가 정한다")은 같게 맞춰둔 것이다 — 한쪽만 기본값을 뒤집으면 "폰에서만 한도가 빨리 닳는다"가 된다.

#### 긴급 시청 횟수(`emergency_uses`) — 버킷 합산 규칙

횟수가 기기별 로컬 값으로만 남으면 PC에서 3회를 다 써도 폰에서 다시 3회를 쓸 수 있다. 커밋먼트 장치가 기기 하나 바꾸는 걸로 무력화되는 구멍이라, 시간과 똑같이 델타로 올리고 합계로 되돌려 받는다. **양쪽 클라이언트가 같은 규칙으로 판정해야 하는 계약**이다:

1. **합산 구간은 하루가 아니라 리셋 버킷 전체다.** 허용 횟수는 일/주/월 단위로 리셋되는데 `daily_usage`는 날짜별 행이라, `weekly`/`monthly`면 버킷 시작일부터 오늘까지의 `emergency_uses`를 다 더해야 잔여 횟수가 맞는다. `daily`는 오늘 행 하나뿐이라 RPC 반환값을 그대로 쓴다.
2. **버킷 시작일은 리셋 판정에 쓰는 키와 같은 함수에서 나와야 한다** (확장 `lib/dateRollover.js`의 `emergencyResetDate`, 안드로이드 `emergencyResetKey` → `emergencyBucketStartDate`). 구간 기준을 따로 만들면 리셋 시점과 합산 구간이 어긋나 잔여 횟수가 조용히 틀어진다.
3. **클램프는 날짜별이 아니라 버킷 합계에 한 번만 건다.** 날짜별로 0에서 자르면, 아직 보고 안 된 지난 날이 다른 기기 몫을 상쇄하는 경우 두 클라이언트가 서로 다른 잔여 횟수를 보여준다.
4. **"내가 보고한 몫"은 지난 날 = 로컬 기록, 오늘 = 실제로 서버에 밀어넣은 값**으로 센다(`reportedEmergencyUsesInBucket`). 아직 안 보낸 오늘분까지 내 몫으로 치면 그만큼이 "다른 기기 몫"에서 빠져 잔여가 실제보다 넉넉해진다 — 우회 구멍이 그대로 남는다.
5. **네트워크가 긴급 시청을 막으면 안 된다.** 서버 합계는 버킷 키와 함께 로컬(확장 `chrome.storage`, 안드로이드 DataStore)에 캐시하고, 조회 실패 시엔 캐시를 건드리지 않고 빠진다(마지막으로 성공한 값이 남는다). 서버 합계를 아예 얻은 적이 없으면 로컬 값만으로 동작한다 — 서버 합계는 "얻어지면 반영되는 보너스"이지 전제가 아니다.
6. **로그아웃은 버킷 캐시를 남기고, 계정 삭제만 지운다 — 양쪽 동일.** 로그아웃에서 캐시를 지우면 잔여 판정이 로컬 값으로 폴백해 다른 기기가 이미 쓴 횟수가 통째로 되살아난다. 즉 로그아웃 버튼이 곧 "횟수 리필 버튼"이 된다(확장에 실제로 있던 구멍이고, `d96801d`에서 닫았다). 캐시에는 버킷 시작일이 같이 붙어 있어 버킷이 끝나면 자동으로 무시되므로, 남겨두는 것만으로 "버킷이 끝나면 사라진다"가 성립한다. 반면 계정 **삭제**는 지운다: 계정 행이 cascade로 사라진 마당에 남겨두면 존재하지 않는 기기 때문에 횟수가 깎인다. **대신 이 기기 자신의 카운트다운(`emergency_uses_today` / 안드로이드 대응 키)은 삭제에서도 남긴다** — 그것까지 지우면 "탈퇴 후 재가입"이 허용 횟수를 full로 되돌리는 우회가 된다. 목록은 확장 `lib/accountReset.js`(`SIGN_OUT_REMOVED_KEYS` / `ACCOUNT_DELETE_REMOVED_KEYS` / `ACCOUNT_DELETE_PRESERVED_KEYS`, 테스트로 고정)와 안드로이드 `AppState.clearAccountData`가 원본이다. **`chrome.storage.local.clear()` 같은 통째 삭제를 쓰면 안 된다** — 남겨야 할 키가 조용히 딸려 나간다.

안드로이드는 이 판정을 `consumeEmergency`의 edit 트랜잭션 안에서 읽어 게이트가 원자적으로 유지된다. 확장은 `requestEmergency` 핸들러가 계정 단위 잔여로 게이트하고 로컬 카운터를 함께 감소시킨다.

#### 계정 전환 — 무엇을 지우고 무엇을 남기나

로그아웃·계정 삭제에 이어 **세 번째 경우**가 있다: 로그아웃도 삭제도 아닌 채로 기기의 주인이 바뀌는 것(A가 로그아웃하고 B가 로그인하는 공용 PC). 로그아웃이 진단 기록만 지우므로, 가드가 없으면 B가 A의 28일 기록을 보고, A의 `dailyUsageSynced*`("이미 보고한 몫")가 B의 첫 델타 기준선이 되어 **B의 그날 사용량이 조용히 서버에 안 올라가며**(음수 델타는 0으로 잘린다), A의 `settingsCache`가 남아 B가 A의 하드코어 잠금을 물려받는다.

**계약의 기준은 "로그아웃했는가"가 아니라 "주인이 바뀌었는가"다.**

1. 기기가 로컬 데이터의 주인을 표식으로 남긴다 — 확장은 `chrome.storage.local`의 `accountUserId`(`ACCOUNT_OWNER_KEY`). 값은 로그인한 `user_id`이고 **로그아웃해도 남긴다**(지우면 다음 로그인이 전부 "표식 없는 첫 로그인"으로 보여 가드가 통째로 무력해진다).
2. **표식이 없으면 아무것도 지우지 않는다.** 가드 이전부터 쓰던 기기이거나 이 기기의 첫 로그인이라, 여기서 지우면 멀쩡히 쓰던 사람이 업데이트 한 번에 기록을 잃는다.
3. **같은 user_id면 아무것도 지우지 않는다.** 로컬 전용 기록은 서버에서 되받아올 수 없어 한 번 지우면 영영 사라진다.
4. **다른 user_id면 계정에서 온 값을 지운다** — 목록은 계정 삭제와 같고 주인 표식만 뺀다(곧바로 새 주인으로 덮어쓰므로): `settingsCache`, 오프라인 대기분, `usage_history*`·`emergency_history`·`limit_history`·`local_current_date`, `dailyUsageSync*`·`dailyUsageCombined*` 동기화 마커, `emergencyUsesBucket*`, 진단 기록.
5. **남기는 것은 전부 "계정이 아니라 이 기기에서 온 값"이다**: 지금 걸려 있는 차단(`isYoutubeBlocked`/`trackingBlocked`/`isManuallyBlocked`/`shortsLimitBlocked`), 진행 중이거나 예약된 집중 세션, 긴급 시청 창·마지막 부여 시각·**이 기기의 남은 횟수 카운트다운**(`emergency_uses_today`/`last_emergency_date`), 알람 장부, 예약 차단 마커, 대시보드 보기 설정. 이걸 지우면 "다른 계정으로 로그인"이 지금 나를 막고 있는 차단에서 빠져나가는 길이 된다.
6. **긴급 버킷 캐시는 로그아웃에서는 남기고 계정 전환에서는 지운다.** 그 캐시는 "이 계정의 다른 기기가 이번 버킷에서 쓴 횟수"라 주인이 바뀌면 남의 숫자다 — 남겨두면 B의 잔여가 A의 폰이 쓴 만큼 깎인다. 로그아웃에서 남기는 이유였던 "로그아웃 = 횟수 리필" 우회는 여기서도 막혀 있다: 리필을 실제로 막는 건 5번의 `emergency_uses_today`이고, 이 경로는 **다른 계정으로 진짜 로그인해야** 도달한다.
7. **서버에 settings 행이 없으면(갓 가입) 캐시를 기본값으로 리셋한다.** 예전에는 `if (!data) return;`으로 빠져서 직전 계정의 설정이 그대로 살아남았다. "행 없음"은 "설정이 기본값"이라는 뜻이지 "직전 값을 유지하라"가 아니다. 단 **조회 실패는 이 경로로 오면 안 된다** — 오프라인이라고 설정이 기본값으로 풀리면 안 되므로, 실패와 "행 없음"을 구분하는 책임은 호출부에 있다.

목록의 원본은 확장 `lib/accountReset.js`(`ACCOUNT_SWITCH_REMOVED_KEYS` / `ACCOUNT_SWITCH_PRESERVED_KEYS` / `planAccountSwitch`, 테스트로 고정)이다. **`chrome.storage.local.clear()` 같은 통째 삭제를 쓰면 안 된다** — 5번의 키가 조용히 딸려 나간다.

**안드로이드에는 아직 이 가드가 없다.** `AppState.clearAccountData`는 로그아웃·계정 삭제 경로만 있고, 주인 표식(`accountUserId` 대응 키)이나 전환 판정에 해당하는 코드가 없다. 확장에서 A→B 전환을 하면 정리되지만 같은 폰에서 계정을 바꾸면 위 세 가지 오염이 그대로 남는다. 포팅할 때는 위 5번(남기는 키)을 먼저 맞춰야 한다 — 지우는 쪽만 베끼면 계정 전환이 집중 모드 탈출구가 된다.

### `streaks` (user_id PK, 1행/유저)

`current_streak`, `best_streak`, `last_result_date`, `total_success_days`, `xp`, 그리고 완벽한 날(긴급 시청 0회) 집계인 `perfect_days`, `current_perfect_streak`, `best_perfect_streak`.

- 긴급 시청을 쓴 날은 `current_streak`은 이어지고 완벽한 날 3개 값만 끊긴다 (판정 규칙은 확장 `gamification.js`의 `isDaySuccess`/`isPerfectDay`와 안드로이드 `Gamification.kt`의 동명 함수 — 양쪽 동일 규칙, 한쪽 고치면 다른 쪽도 맞춰야 함. [FEATURES.md](FEATURES.md) 게임화 규칙 참고).
- 긴급 시청 시간과 횟수는 **날짜별 로컬 기록**(확장 `emergency_history`, 안드로이드 `emergency_history`/`emergency_uses_history` DataStore 키)과 **서버 합계**(`daily_usage.emergency_ms` / `emergency_uses`) 양쪽에 있다. 롤오버 판정(`isDaySuccess`/`isPerfectDay`)은 로컬 기록으로 한다 — 그 날을 실제로 시청한 기기가 자기 몫을 아는 유일한 쪽이기 때문. 서버 합계는 잔여 횟수 게이트(위 daily_usage 절)와 대시보드 병합에 쓴다.
- 대시보드의 날짜별 병합은 시간과 횟수를 **둘 다** 서버에서 가져와 `max(로컬, 서버)`로 합친다(확장 `lib/historyMerge.js`, 안드로이드 `sync/HistoryMerge.kt`). 횟수를 안 읽던 시절에는 폰에서 긴급 시청을 발급만 받고 안 본 날(`emergency_ms = 0`, `emergency_uses = 1`)이 확장 히트맵에서 "완벽한 날"로 둔갑했다. 로컬·서버 어디에도 기록이 없는 날만 횟수를 "모름"으로 두고 화면에 표시하지 않는다 — 0회로 채우면 "긴급 시청 없이 넘긴 날"이라는 없는 사실을 적게 된다.

- 자정(4시 컷오프 기준) 롤오버 때 그날 성공/실패 판정 후 갱신, 두 클라이언트 모두 구현됨(안드로이드 `SyncRepository.pushStreak`, 확장 `gamification.js`).
- **정산에 쓰는 한도는 그날의 스냅샷이다** (확장 `lib/limitHistory.js`의 `planRolloverLimits`, 안드로이드 `limit/LimitHistory.kt`의 동명 함수 — 한쪽만 고치면 갈라지는 계약). 히트맵은 스냅샷으로 그리는데 정산만 현재 설정으로 하면, 며칠 안 켠 사이 한도를 바꿨을 때 **같은 날을 히트맵과 스트릭이 반대로 판정한다.** 스냅샷이 없는 날(기능 이전, 또는 그 기기에서 시청하지 않은 날)만 현재 설정으로 근사한다. `streaks` 행은 두 클라이언트가 공유하므로 규칙이 갈라지면 나중에 로그인한 기기가 앞선 판정을 덮어쓴다.
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

## 동기화 진단 로그 (서버에 저장되지 않음)

이 절만 서버 테이블이 아니라 **클라이언트 로컬** 이야기지만, 두 클라이언트가 같은 규칙을 지켜야 하는 계약이라 여기에 둔다. 동기화 실패가 각 기기 로그(확장 `console.error`, 안드로이드 Logcat)로만 흘러가면 며칠째 Supabase 동기화가 막힌 기기와 멀쩡한 기기를 구분할 수 없다 — 확장은 MV3 서비스워커가 유휴 시 죽으면서 콘솔까지 날아가고, 폰은 아예 콘솔을 볼 방법이 없다. 외부 크래시 리포팅은 붙이지 않기로 했으므로 그 자리를 각 기기의 링버퍼가 메운다.

**두 구현이 어긋나면 안 되는 값들** (확장 `lib/syncDiagnostics.js` ↔ 안드로이드 `diagnostics/SyncDiagnostics.kt`):

| 항목 | 값 | 왜 같아야 하나 |
|---|---|---|
| 이벤트 종류 | `sync_settings`, `sync_streak`, `sync_usage`, `emergency_fetch`, `auth`, `monitor` — **문자열까지 동일** | 두 기기에서 모은 기록을 나란히 놓고 읽어야 "폰은 멀쩡한데 크롬만 이상하다"는 판단이 선다 |
| 버퍼 크기 | 50건 (`DIAGNOSTIC_CAPACITY`), 넘치면 오래된 것부터 폐기 | |
| 중복 처리 | 같은 (종류 + 코드)는 줄을 쌓지 않고 카운터를 올림 | 같은 실패가 반복될 때 한쪽만 버퍼가 밀려나가면 비교가 안 됨 |
| 지연 경고 임계값 | 마지막 성공으로부터 24시간 (`SYNC_STALE_THRESHOLD_MS` / `SYNC_STALE_THRESHOLD_MILLIS`) | 확장은 팝업, 안드로이드는 홈 화면에 조용한 한 줄로 표시 |
| 성공 기록 | 실패만 버퍼에 넣고 성공은 타임스탬프만 갱신 | 성공까지 넣으면 버퍼가 노이즈로 차서 실패가 밀려난다 |

저장 인코딩만 다르다 — 확장은 `chrome.storage.local`이 구조화된 값을 담으므로 배열/객체, 안드로이드는 DataStore라 `data/Encoding.kt`와 같은 방식의 문자열 인코딩(구분자 `U+001E`/`U+001F`)을 쓴다. 로그아웃/계정 삭제 시 양쪽 다 지운다(마지막 성공 시각이 다음 계정의 24시간 판정에 끼어들면 안 되므로).

### 민감정보 금지 — 새 실패 경로를 붙일 때 반드시 지킬 것

이 버퍼는 사용자가 화면에서 읽고 "복사" 버튼으로 클립보드에 담아 남에게 붙여넣는 것을 전제로 한다. access token, 이메일, `user_id`, anon/service key 같은 값이 한 번 들어가면 그대로 유출이다. **PostgREST/GoTrue의 오류 문구에는 조건에 걸린 값(이메일, uuid)이 그대로 실려 오므로 서버 메시지 본문을 옮겨 담으면 안 된다.** 방어는 두 겹이고, 2번은 1번의 대체재가 아니다:

1. **허용 목록** — `summarizeFailure()`가 예외/오류 이름 + `[45]xx` HTTP 상태 + PostgREST 코드(`PGRSTxxx`)만 뽑아낸다. 상태 코드 정규식은 앞뒤가 영숫자면 잡지 않아 uuid 조각에서 세 자리를 잘라오지 않는다(양쪽 동일 규칙).
2. **redact** — `sanitizeDiagnosticCode()`가 이메일·UUID·JWT 모양을 `[redacted]`로 바꾸고, 제어문자를 공백으로 접고, 48자로 자른다. 이벤트를 넣는 유일한 통로가 항상 이 함수를 거치므로 저장된 문자열엔 그 패턴이 남을 수 없다.

3. **읽는 쪽에서도 한 번 더** — 저장할 때만 거르면 손상되거나 손으로 편집된 저장소가 그대로 화면과 클립보드로 나간다. 그래서 디코드 경로(확장 `normalizeDiagnosticEvents`, 안드로이드 `decodeDiagnosticEvents` → 같은 이름의 정규화 함수)도 sanitize를 다시 걸고 버퍼 크기로 자른 뒤에야 값을 돌려주며, "복사" 리포트도 그 정규화된 값으로 만든다.

새 실패 경로를 진단에 연결할 때 원문 문자열을 `code`에 직접 넣으면 이 계약이 깨진다. 반드시 이 함수들을 통과시킬 것.

## 스키마 바꿀 때 체크리스트

이번에 두 번 썼다(`daily_usage.emergency_uses`, `settings.shorts_limit_ms`). 그때 걸린 것들을 합쳐 놓는다.

1. `extension/supabase/schema.sql`의 `create table`과 **아래쪽 `alter table ... add column if not exists ...` 두 곳 모두**에 추가한다. 새 프로젝트는 `create table`로, 이미 돌고 있는 프로젝트는 `alter`로 붙기 때문에 한쪽만 고치면 둘 중 하나가 조용히 어긋난다.
2. **기본값은 "기존 동작과 같음"이 되게 잡는다.** `shorts_limit_ms`의 `0`(=미설정)처럼, 컬럼이 붙기만 하고 아무도 값을 넣지 않은 상태에서 사용자가 갑자기 차단당하면 안 된다.
3. 컬럼을 **누가 소유하는지 정한다.** 브라우저 전용이면 안드로이드 `sync/RemoteModels.kt`의 `SETTINGS_COLUMNS`에 **넣지 않는다**(PostgREST upsert가 본문에 없는 키를 건드리지 않는 성질에 의존). `RemoteModelsTest`의 "the payload carries only the columns this client owns"도 같이 갱신.
4. 소유하는 쪽 클라이언트의 select/upsert 컬럼 목록, 기본값 리셋 경로(옵션 페이지의 초기화 등), UI 입력을 다 훑는다. **차단을 약화시킬 수 있는 컬럼이면 하드코어 판정(`lib/hardcoreLock.js`의 `findHardcoreViolations`)에 비교 규칙을 추가한다** — "어느 방향이 약화인가"를 적어주지 않으면 그 컬럼은 하드코어 중에도 자유롭게 느슨해진다(위 "하드코어 잠금 범위"). 계정에서 온 값이면 `lib/accountReset.js`의 삭제/보존 목록에도 분류해 넣는다(위 "계정 전환").
5. **RPC 시그니처를 건드리면** `create or replace`로 안 될 수 있다. 인자만 늘어나면 replace가 되지만, 반환 테이블에 컬럼이 붙으면 반환 타입이 달라져 `cannot change return type of existing function`이 난다 → 옛 시그니처를 **전부** `drop function if exists ...`로 지운 뒤 새로 만든다. 이름이 같은 함수가 여럿 남으면 인자를 적게 넘기는 호출이 `function is not unique`로 죽는다.
6. **drop 하면 권한도 같이 사라진다.** 새 시그니처에 `revoke execute ... from public` / `grant execute ... to authenticated`를 다시 건다.
7. RPC를 바꿨다면 **두 클라이언트를 같은 변경에서 같이 올린다.** 옛 빌드가 남은 기기는 그 동기화가 통째로 실패한다(진단 로그에 남는다).
8. 이 문서의 표와 관련 절, 그리고 [FEATURES.md](FEATURES.md)의 기능 표를 갱신한다.

테이블 자체를 새로 추가할 때는 `user_id ... references auth.users(id) on delete cascade`와 RLS 정책을 빠뜨리지 말 것 — 계정 삭제 Edge Function은 테이블별 delete를 하지 않고 이 cascade에만 의존한다(위 "네 테이블은 알아서 지워진다" 참고).

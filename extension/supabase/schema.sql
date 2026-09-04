-- TubeLimiter v2 스키마. Supabase 대시보드 SQL Editor에서 그대로 실행하세요.

create table if not exists daily_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  date date not null,
  usage_ms bigint not null default 0,
  shorts_ms bigint not null default 0,
  -- usage_ms 중 긴급 시청으로 본 시간. usage_ms에서 빼둔 값이 아니라 "그중 얼마"인지를 나타낸다
  -- (총 시청시간은 사실대로 두고, 스트릭 판정에서만 이만큼을 빼준다).
  emergency_ms bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, date)
);

-- 기존 설치에 긴급 시청 시간 컬럼 추가 (이미 있으면 무시)
alter table daily_usage add column if not exists emergency_ms bigint not null default 0;

create table if not exists settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  daily_limit_ms bigint not null default 1800000, -- 기본 30분
  daily_limit_by_day jsonb not null default '{}'::jsonb,
  daily_limit_reset_frequency text not null default 'daily',
  always_block_shorts boolean not null default false,
  whitelist jsonb not null default '[]'::jsonb,
  emergency_config jsonb not null default '{"dailyUses": 3, "resetFrequency": "daily"}'::jsonb,
  alarm_interval_minutes integer not null default 0,
  alarm_milestones_enabled boolean not null default true,
  hardcore_mode boolean not null default false,
  hardcore_disable_requested_at timestamptz,
  updated_at timestamptz not null default now()
);

-- 기존 설치에 알람 컬럼 추가 (이미 있으면 무시)
alter table settings add column if not exists alarm_interval_minutes integer not null default 0;
alter table settings add column if not exists alarm_milestones_enabled boolean not null default true;
-- 하드코어 모드: 켜져 있으면 한도 설정이 잠기고, 켜져 있을 때만 스트릭이 기록된다.
-- 끄는 것도 즉시 반영되면 충동적으로 껐다 켰다 할 수 있으므로, 끄기는 요청 시각을 남겨두고
-- HARDCORE_DISABLE_COOLDOWN_MS(1시간)가 지난 뒤에야 실제로 꺼지도록 한다.
alter table settings add column if not exists hardcore_mode boolean not null default false;
alter table settings add column if not exists hardcore_disable_requested_at timestamptz;
-- 예약 차단(요일별 반복 시간대 자동 차단). 배열 원소: {id, label, days[7](일=0), startMinute,
-- endMinute(0~1439), enabled}. endMinute <= startMinute면 자정을 넘기는 구간이고, 그 구간은
-- days가 가리키는 "시작 요일"에 속한다. 하루 4시 컷오프(usage 판정용)와는 무관한 실제 시계
-- 기준 — 확장 lib/schedule.js, 안드로이드 limit/ScheduleRules.kt 둘 다 이 규칙으로 판정한다.
alter table settings add column if not exists scheduled_blocks jsonb not null default '[]'::jsonb;

create table if not exists streaks (
  user_id uuid primary key references auth.users(id) on delete cascade,
  current_streak integer not null default 0,
  best_streak integer not null default 0,
  last_result_date date,
  total_success_days integer not null default 0,
  xp integer not null default 0,
  -- 완벽한 날 = 긴급 시청을 한 번도 안 쓰고 한도를 지킨 날. 긴급 시청을 쓴 날은 current_streak은
  -- 그대로 이어지고(스트릭을 끊지 않음) 이 세 값만 끊긴다 — 스트릭/완벽한 날 이원화.
  perfect_days integer not null default 0,
  current_perfect_streak integer not null default 0,
  best_perfect_streak integer not null default 0,
  updated_at timestamptz not null default now()
);

-- 기존 설치에 완벽한 날 컬럼 추가 (이미 있으면 무시)
alter table streaks add column if not exists perfect_days integer not null default 0;
alter table streaks add column if not exists current_perfect_streak integer not null default 0;
alter table streaks add column if not exists best_perfect_streak integer not null default 0;

create table if not exists achievements (
  user_id uuid not null references auth.users(id) on delete cascade,
  key text not null,
  unlocked_at timestamptz not null default now(),
  primary key (user_id, key)
);

alter table daily_usage enable row level security;
alter table settings enable row level security;
alter table streaks enable row level security;
alter table achievements enable row level security;

drop policy if exists "daily_usage: owner rw" on daily_usage;
create policy "daily_usage: owner rw" on daily_usage
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "settings: owner rw" on settings;
create policy "settings: owner rw" on settings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "streaks: owner rw" on streaks;
create policy "streaks: owner rw" on streaks
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "achievements: owner rw" on achievements;
create policy "achievements: owner rw" on achievements
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- 여러 기기(확장 + 안드로이드)가 같은 계정으로 동시에 usage_ms를 upsert하면 나중 쓴 쪽이
-- 먼저 쓴 쪽 값을 덮어써서 한쪽 기기 사용시간이 사라진다. 그래서 daily_usage는 절대 값을
-- 덮어쓰지 않고, 이 함수로 "내가 전에 보고한 뒤로 늘어난 만큼"만 더한다.
-- security invoker(기본값)라 auth.uid()는 호출자 세션 그대로 evaluate되고, daily_usage의
-- RLS 정책이 그대로 적용된다.
-- p_emergency_delta_ms가 붙기 전의 3인자 버전을 남겨두면 이름이 같은 함수가 둘이 되어,
-- 인자 3개로 부르는 기존 클라이언트(안드로이드)가 "function is not unique"로 실패한다.
-- 기본값이 있는 4인자 버전 하나로 대체하면 3인자 호출도 그대로 동작한다.
drop function if exists increment_daily_usage(date, bigint, bigint);

create or replace function increment_daily_usage(
  p_date date,
  p_usage_delta_ms bigint,
  p_shorts_delta_ms bigint default 0,
  p_emergency_delta_ms bigint default 0
)
returns table (usage_ms bigint, shorts_ms bigint, emergency_ms bigint)
language plpgsql
set search_path = public
as $$
begin
  return query
  insert into daily_usage (user_id, date, usage_ms, shorts_ms, emergency_ms, updated_at)
  values (
    auth.uid(),
    p_date,
    greatest(p_usage_delta_ms, 0),
    greatest(p_shorts_delta_ms, 0),
    greatest(p_emergency_delta_ms, 0),
    now()
  )
  on conflict (user_id, date) do update
    set usage_ms = daily_usage.usage_ms + greatest(excluded.usage_ms, 0),
        shorts_ms = daily_usage.shorts_ms + greatest(excluded.shorts_ms, 0),
        emergency_ms = daily_usage.emergency_ms + greatest(excluded.emergency_ms, 0),
        updated_at = now()
  returning daily_usage.usage_ms, daily_usage.shorts_ms, daily_usage.emergency_ms;
end;
$$;

-- 새 함수는 기본으로 PUBLIC(anon 포함)에도 EXECUTE 권한이 열린다. 익명 호출은 auth.uid()가
-- null이라 daily_usage.user_id(not null)에서 에러로 막히긴 하지만, 최소 권한 원칙상 애초에
-- 로그인한 사용자만 부를 수 있게 좁혀둔다.
revoke execute on function increment_daily_usage(date, bigint, bigint, bigint) from public;
grant execute on function increment_daily_usage(date, bigint, bigint, bigint) to authenticated;

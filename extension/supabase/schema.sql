-- TubeLimiter v2 스키마. Supabase 대시보드 SQL Editor에서 그대로 실행하세요.

create table if not exists daily_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  date date not null,
  usage_ms bigint not null default 0,
  shorts_ms bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, date)
);

create table if not exists settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  daily_limit_ms bigint not null default 1800000, -- 기본 30분
  daily_limit_by_day jsonb not null default '{}'::jsonb,
  daily_limit_reset_frequency text not null default 'daily',
  always_block_shorts boolean not null default false,
  whitelist jsonb not null default '[]'::jsonb,
  emergency_config jsonb not null default '{"dailyUses": 3, "resetFrequency": "daily"}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists streaks (
  user_id uuid primary key references auth.users(id) on delete cascade,
  current_streak integer not null default 0,
  best_streak integer not null default 0,
  last_result_date date,
  total_success_days integer not null default 0,
  xp integer not null default 0,
  updated_at timestamptz not null default now()
);

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

create policy "daily_usage: owner rw" on daily_usage
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "settings: owner rw" on settings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "streaks: owner rw" on streaks
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "achievements: owner rw" on achievements
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

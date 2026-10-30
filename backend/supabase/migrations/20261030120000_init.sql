-- Flight View initial schema.
-- Users sign in anonymously on first launch (they can link an email later),
-- so every row belongs to auth.uid() and Row Level Security does the rest.

create extension if not exists pgcrypto;

-- Profiles ------------------------------------------------------------------

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text check (display_name is null or char_length(display_name) between 1 and 40),
  units text not null default 'imperial' check (units in ('imperial', 'metric')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

create policy "profiles: read own" on public.profiles
  for select to authenticated using ((select auth.uid()) = id);
create policy "profiles: insert own" on public.profiles
  for insert to authenticated with check ((select auth.uid()) = id);
create policy "profiles: update own" on public.profiles
  for update to authenticated using ((select auth.uid()) = id) with check ((select auth.uid()) = id);

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger profiles_touch before update on public.profiles
  for each row execute function public.touch_updated_at();

-- Create a profile row for every new user (anonymous or not).
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id) values (new.id) on conflict (id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- Spotting log ----------------------------------------------------------------
-- What a user tapped and saved. Location is stored only at tile precision
-- (0.1 degree, ~11 km), matching what the API ever sees.

create table public.spots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  icao24 text not null check (icao24 ~ '^~?[0-9a-f]{6}$'),
  callsign text check (callsign is null or char_length(callsign) <= 10),
  registration text check (registration is null or char_length(registration) <= 12),
  type_code text check (type_code is null or char_length(type_code) <= 8),
  type_name text check (type_name is null or char_length(type_name) <= 80),
  operator text check (operator is null or char_length(operator) <= 80),
  altitude_m real check (altitude_m is null or altitude_m between -500 and 30000),
  slant_range_km real check (slant_range_km is null or slant_range_km between 0 and 500),
  elevation_deg real check (elevation_deg is null or elevation_deg between -90 and 90),
  tile_lat numeric(5, 2) check (tile_lat between -90 and 90),
  tile_lon numeric(6, 2) check (tile_lon between -180 and 180),
  spotted_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create index spots_user_time_idx on public.spots (user_id, spotted_at desc);

alter table public.spots enable row level security;

create policy "spots: read own" on public.spots
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "spots: insert own" on public.spots
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "spots: delete own" on public.spots
  for delete to authenticated using ((select auth.uid()) = user_id);

-- Stats for the logbook header. Security invoker, so RLS applies.
create or replace function public.my_spot_stats()
returns table (total_spots bigint, unique_aircraft bigint, unique_types bigint, first_spot timestamptz)
language sql
stable
security invoker
set search_path = ''
as $$
  select count(*), count(distinct icao24), count(distinct type_code), min(spotted_at)
  from public.spots
  where user_id = (select auth.uid());
$$;

revoke all on function public.my_spot_stats() from public, anon;
grant execute on function public.my_spot_stats() to authenticated;

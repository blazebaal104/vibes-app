-- Vibes backend schema. Run once in Supabase → SQL Editor (project perlospam696@gmail.com).
-- Everything is prefixed vibes_ and does not touch existing tables.

create table public.vibes_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[a-z0-9_.]{3,20}$'),
  display_name text not null default '',
  bio text not null default '' check (char_length(bio) <= 160),
  avatar_color text not null default '#B84DFF',
  avatar_emoji text not null default '🎧',
  invite_code text not null unique default upper(substr(md5(gen_random_uuid()::text), 1, 8)),
  top4 jsonb not null default '[]'::jsonb,
  now_playing jsonb,
  last_seen timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create table public.vibes_friendships (
  user_id uuid not null references public.vibes_profiles(id) on delete cascade,
  friend_id uuid not null references public.vibes_profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);

create table public.vibes_friend_requests (
  id uuid primary key default gen_random_uuid(),
  from_id uuid not null references public.vibes_profiles(id) on delete cascade,
  to_id uuid not null references public.vibes_profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','declined')),
  created_at timestamptz not null default now(),
  unique (from_id, to_id),
  check (from_id <> to_id)
);

create table public.vibes_messages (
  id bigint generated always as identity primary key,
  sender_id uuid not null references public.vibes_profiles(id) on delete cascade,
  receiver_id uuid not null references public.vibes_profiles(id) on delete cascade,
  kind text not null default 'text' check (kind in ('text','track','playlist','challenge','image','video','audio')),
  body text not null default '' check (char_length(body) <= 4000),
  payload jsonb,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  read_at timestamptz
);
create index vibes_messages_pair_idx on public.vibes_messages (least(sender_id, receiver_id), greatest(sender_id, receiver_id), created_at desc);
create index vibes_messages_receiver_idx on public.vibes_messages (receiver_id, created_at desc);
create index vibes_messages_sender_idx on public.vibes_messages (sender_id);

create table public.vibes_shared_playlists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.vibes_profiles(id) on delete cascade,
  name text not null,
  tracks jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now()
);
create index vibes_shared_playlists_owner_idx on public.vibes_shared_playlists (owner_id);

create table public.vibes_game_lobbies (
  id uuid primary key default gen_random_uuid(),
  game text not null check (game in ('guess','blindtest')),
  host_id uuid not null references public.vibes_profiles(id) on delete cascade,
  guest_id uuid not null references public.vibes_profiles(id) on delete cascade,
  status text not null default 'waiting' check (status in ('waiting','playing','finished','cancelled')),
  state jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index vibes_game_lobbies_host_idx on public.vibes_game_lobbies (host_id);
create index vibes_game_lobbies_guest_idx on public.vibes_game_lobbies (guest_id);
create index vibes_friendships_friend_idx on public.vibes_friendships (friend_id);
create index vibes_friend_requests_to_idx on public.vibes_friend_requests (to_id);

create or replace function public.vibes_are_friends(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from vibes_friendships where user_id = a and friend_id = b);
$$;

alter table public.vibes_profiles enable row level security;
alter table public.vibes_friendships enable row level security;
alter table public.vibes_friend_requests enable row level security;
alter table public.vibes_messages enable row level security;
alter table public.vibes_shared_playlists enable row level security;
alter table public.vibes_game_lobbies enable row level security;

create policy "vibes profiles readable" on public.vibes_profiles for select to authenticated using (true);
create policy "vibes own profile insert" on public.vibes_profiles for insert to authenticated with check (id = (select auth.uid()));
create policy "vibes own profile update" on public.vibes_profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy "vibes my friendships" on public.vibes_friendships for select to authenticated using (user_id = (select auth.uid()));
create policy "vibes remove friend" on public.vibes_friendships for delete to authenticated using (user_id = (select auth.uid()) or friend_id = (select auth.uid()));

create policy "vibes my requests" on public.vibes_friend_requests for select to authenticated using (from_id = (select auth.uid()) or to_id = (select auth.uid()));
create policy "vibes send request" on public.vibes_friend_requests for insert to authenticated with check (from_id = (select auth.uid()));
create policy "vibes delete request" on public.vibes_friend_requests for delete to authenticated using (from_id = (select auth.uid()) or to_id = (select auth.uid()));

create policy "vibes my messages" on public.vibes_messages for select to authenticated using (sender_id = (select auth.uid()) or receiver_id = (select auth.uid()));
create policy "vibes send to friends" on public.vibes_messages for insert to authenticated
  with check (sender_id = (select auth.uid()) and public.vibes_are_friends(sender_id, receiver_id));

create policy "vibes shared playlists readable" on public.vibes_shared_playlists for select to authenticated using (true);
create policy "vibes share playlist" on public.vibes_shared_playlists for insert to authenticated with check (owner_id = (select auth.uid()));

create policy "vibes my lobbies" on public.vibes_game_lobbies for select to authenticated using (host_id = (select auth.uid()) or guest_id = (select auth.uid()));
create policy "vibes create lobby" on public.vibes_game_lobbies for insert to authenticated
  with check (host_id = (select auth.uid()) and public.vibes_are_friends(host_id, guest_id));

create or replace function public.vibes_add_friend_by_code(code text) returns public.vibes_profiles
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); other public.vibes_profiles;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select * into other from vibes_profiles where invite_code = upper(trim(code));
  if other.id is null then raise exception 'Codice non valido'; end if;
  if other.id = me then raise exception 'Questo è il tuo codice'; end if;
  insert into vibes_friendships(user_id, friend_id) values (me, other.id), (other.id, me) on conflict do nothing;
  delete from vibes_friend_requests where (from_id = me and to_id = other.id) or (from_id = other.id and to_id = me);
  return other;
end $$;

create or replace function public.vibes_accept_request(request_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r vibes_friend_requests;
begin
  select * into r from vibes_friend_requests where id = request_id and to_id = me and status = 'pending';
  if r.id is null then raise exception 'Richiesta non trovata'; end if;
  insert into vibes_friendships(user_id, friend_id) values (r.from_id, r.to_id), (r.to_id, r.from_id) on conflict do nothing;
  delete from vibes_friend_requests where id = r.id;
end $$;

create or replace function public.vibes_mark_delivered(from_user uuid) returns void
language sql security definer set search_path = public as $$
  update vibes_messages set delivered_at = now()
  where receiver_id = auth.uid() and sender_id = from_user and delivered_at is null;
$$;

create or replace function public.vibes_mark_read(from_user uuid) returns void
language sql security definer set search_path = public as $$
  update vibes_messages set read_at = now(), delivered_at = coalesce(delivered_at, now())
  where receiver_id = auth.uid() and sender_id = from_user and read_at is null;
$$;

create or replace function public.vibes_lobby_update(lobby_id uuid, new_status text, patch jsonb) returns public.vibes_game_lobbies
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); l vibes_game_lobbies;
begin
  select * into l from vibes_game_lobbies where id = lobby_id for update;
  if l.id is null or (l.host_id <> me and l.guest_id <> me) then raise exception 'lobby non trovata'; end if;
  update vibes_game_lobbies
     set state = l.state || coalesce(patch, '{}'::jsonb),
         status = coalesce(new_status, l.status),
         updated_at = now()
   where id = lobby_id returning * into l;
  return l;
end $$;

create or replace function public.vibes_claim_round(lobby_id uuid, round int, winner uuid, reason text) returns boolean
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); l vibes_game_lobbies; k text := 'r' || round;
begin
  select * into l from vibes_game_lobbies where id = lobby_id for update;
  if l.id is null or (l.host_id <> me and l.guest_id <> me) then raise exception 'lobby non trovata'; end if;
  if winner <> l.host_id and winner <> l.guest_id then raise exception 'giocatore non valido'; end if;
  if coalesce(l.state -> 'rounds', '{}'::jsonb) ? k then return false; end if;
  update vibes_game_lobbies
     set state = jsonb_set(l.state, array['rounds'], coalesce(l.state -> 'rounds', '{}'::jsonb) || jsonb_build_object(k, jsonb_build_object('winner', winner, 'reason', reason, 'at', now()))),
         updated_at = now()
   where id = lobby_id;
  return true;
end $$;

revoke execute on function public.vibes_add_friend_by_code(text), public.vibes_accept_request(uuid),
  public.vibes_mark_delivered(uuid), public.vibes_mark_read(uuid), public.vibes_lobby_update(uuid, text, jsonb),
  public.vibes_claim_round(uuid, int, uuid, text), public.vibes_are_friends(uuid, uuid) from public, anon;
grant execute on function public.vibes_add_friend_by_code(text), public.vibes_accept_request(uuid),
  public.vibes_mark_delivered(uuid), public.vibes_mark_read(uuid), public.vibes_lobby_update(uuid, text, jsonb),
  public.vibes_claim_round(uuid, int, uuid, text), public.vibes_are_friends(uuid, uuid) to authenticated;

alter publication supabase_realtime add table public.vibes_messages, public.vibes_game_lobbies, public.vibes_friend_requests, public.vibes_friendships;
alter table public.vibes_messages replica identity full;
alter table public.vibes_game_lobbies replica identity full;

insert into storage.buckets (id, name, public, file_size_limit) values ('vibes-media', 'vibes-media', false, 52428800)
on conflict (id) do nothing;
create policy "vibes media upload own folder" on storage.objects for insert to authenticated
  with check (bucket_id = 'vibes-media' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "vibes media read if participant" on storage.objects for select to authenticated
  using (bucket_id = 'vibes-media' and ((storage.foldername(name))[1] = (select auth.uid())::text or (storage.foldername(name))[2] = (select auth.uid())::text));
create policy "vibes media delete if participant" on storage.objects for delete to authenticated
  using (bucket_id = 'vibes-media' and ((storage.foldername(name))[1] = (select auth.uid())::text or (storage.foldername(name))[2] = (select auth.uid())::text));

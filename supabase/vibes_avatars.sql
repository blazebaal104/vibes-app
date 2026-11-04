-- Vibes: profile photos. Run once in Supabase → SQL Editor (project perlospam696@gmail.com).
alter table public.vibes_profiles add column if not exists avatar_url text;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('vibes-avatars', 'vibes-avatars', true, 2097152, array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

create policy "vibes avatars upload own" on storage.objects for insert to authenticated
  with check (bucket_id = 'vibes-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "vibes avatars update own" on storage.objects for update to authenticated
  using (bucket_id = 'vibes-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "vibes avatars delete own" on storage.objects for delete to authenticated
  using (bucket_id = 'vibes-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);

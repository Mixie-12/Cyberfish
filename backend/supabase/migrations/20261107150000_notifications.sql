-- Push notifications. Whether an event notifies, and whom, is decided here and
-- nowhere else; the Edge Function "notify" asks and only sends.

-- When a device last notified about a source. A table rather than memory, so
-- the coalescing rule survives function restarts and concurrent calls.
create table private.notified_sources (
  device_id uuid not null references public.devices (id) on delete cascade,
  source text not null,
  notified_at timestamptz not null,
  primary key (device_id, source)
);

alter table private.notified_sources enable row level security;

-- Returns the owner's push tokens and the template key (the event kind) when
-- the event should notify, otherwise no rows. Rules: device_online never;
-- device_offline always; a source the owner marked as trusted never; other
-- events at most once per device and source (src_ip, else src_mac) per 10
-- minutes. Calling it records the push, so a repeated call for the same event
-- returns nothing.
create function public.notification_targets_v1(event_id bigint)
  returns table (token text, template text)
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_event public.events;
begin
  select * into v_event
  from public.events as e
  where e.id = notification_targets_v1.event_id;

  if not found or v_event.kind = 'device_online' then
    return;
  end if;

  if v_event.kind <> 'device_offline' then
    if exists (
      select 1
      from public.network_hosts as h
      where h.device_id = v_event.device_id and h.mac = v_event.src_mac and h.trusted
    ) then
      return;
    end if;

    insert into private.notified_sources as n (device_id, source, notified_at)
    values (
      v_event.device_id,
      coalesce(pg_catalog.host(v_event.src_ip), v_event.src_mac::text, ''),
      now()
    )
    on conflict on constraint notified_sources_pkey do update
    set notified_at = excluded.notified_at
    where n.notified_at <= excluded.notified_at - interval '10 minutes';

    if not found then
      return;
    end if;
  end if;

  return query
  select t.token, v_event.kind
  from private.push_tokens as t
  join public.devices as d on d.owner_id = t.user_id
  where d.id = v_event.device_id;
end;
$$;

-- FCM reported the token as no longer registered (app removed, data cleared).
create function public.forget_push_token_v1(token text)
  returns void
  language sql
  security definer
  set search_path = ''
as $$
  delete from private.push_tokens as t where t.token = forget_push_token_v1.token
$$;

grant execute on function public.notification_targets_v1(bigint) to service_role;
grant execute on function public.forget_push_token_v1(text) to service_role;

-- Database webhook: every event is posted, as its id only, to the Edge
-- Function, which asks notification_targets_v1. pg_net sends after commit and
-- never blocks the insert. The URL and shared secret are Vault secrets that
-- `supabase db push` writes from config.toml; without them nothing is sent.
create extension if not exists pg_net with schema extensions;

create function private.post_event_to_notify()
  returns trigger
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_url text;
  v_secret text;
begin
  select s.decrypted_secret into v_url from vault.decrypted_secrets as s where s.name = 'notify_url';
  select s.decrypted_secret into v_secret from vault.decrypted_secrets as s where s.name = 'notify_secret';

  if coalesce(v_url, '') = '' or coalesce(v_secret, '') = '' then
    return null;
  end if;

  perform net.http_post(
    url := v_url,
    body := pg_catalog.jsonb_build_object('event_id', new.id),
    headers := pg_catalog.jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Notify-Secret', v_secret
    )
  );
  return null;
end;
$$;

create trigger events_post_to_notify
  after insert on public.events
  for each row
  execute function private.post_event_to_notify();

-- Coalescing rows a day old decide nothing any more.
create or replace function private.apply_retention()
  returns void
  language sql
  set search_path = ''
as $$
  delete from public.events where occurred_at < now() - interval '90 days';
  delete from public.network_hosts where last_seen_at < now() - interval '90 days';
  delete from private.notified_sources where notified_at < now() - interval '1 day';
$$;

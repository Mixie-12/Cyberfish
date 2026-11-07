-- Weekly summary (ADR 0015): once a week, a push with three counts, which
-- the owner can turn off. pg_cron posts one request per owner to the notify
-- function, as the events webhook posts one per event; weekly_summary_v1
-- decides and counts, the function only words and sends.

-- Settings of an account; no row means the defaults. user_id is never
-- written by a client: it defaults to the caller.
create table public.owner_settings (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  weekly_summary boolean not null default true
);

alter table public.owner_settings enable row level security;

create policy owner_settings_select_own on public.owner_settings
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy owner_settings_insert_own on public.owner_settings
  for insert to authenticated
  with check (user_id = (select auth.uid()));

create policy owner_settings_update_own on public.owner_settings
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

grant select on public.owner_settings to authenticated;
grant insert (weekly_summary), update (weekly_summary) on public.owner_settings to authenticated;

-- When each owner last got a summary.
create table private.weekly_summaries (
  user_id uuid primary key references auth.users (id) on delete cascade,
  sent_at timestamptz not null
);

alter table private.weekly_summaries enable row level security;

-- Incidents as the Alerts timeline groups them (ADR 0013): events from one
-- DeKoi and one source (source MAC, else source IP; DeKoi itself for
-- device_offline and device_online), each within 30 minutes of the previous
-- one. An incident counts when one of its events is critical or a warning
-- and does not come from a device the owner marked as known, as for the
-- Alerts badge.
create function private.incident_count(owner_id uuid, since timestamptz)
  returns integer
  language sql
  stable
  set search_path = ''
as $$
  with sourced as (
    select
      e.device_id,
      e.occurred_at,
      case
        when e.kind in ('device_offline', 'device_online') then ''
        else coalesce(e.src_mac::text, pg_catalog.host(e.src_ip), '')
      end as source,
      e.severity <> 'info' and h.trusted is not true as alerting
    from public.events as e
    join public.devices as d on d.id = e.device_id
    left join public.network_hosts as h on h.device_id = e.device_id and h.mac = e.src_mac
    where d.owner_id = incident_count.owner_id and e.occurred_at >= incident_count.since
  ),
  marked as (
    select
      s.*,
      coalesce(
        s.occurred_at - lag(s.occurred_at) over (partition by s.device_id, s.source order by s.occurred_at)
          > interval '30 minutes',
        true
      ) as starts
    from sourced as s
  ),
  numbered as (
    select
      m.device_id,
      m.source,
      m.alerting,
      count(*) filter (where m.starts) over (partition by m.device_id, m.source order by m.occurred_at) as incident
    from marked as m
  )
  select count(*)::integer
  from (
    select 1
    from numbered as n
    group by n.device_id, n.source, n.incident
    having bool_or(n.alerting)
  ) as incidents
$$;

-- The share of the time since `since` that the owner's registered DeKoi
-- devices were online, in whole percent rounded down, so 100 means no
-- outage; null without a registered device. An outage runs from
-- device_offline to the next device_online, as in the app's uptime strip.
create function private.uptime_percent(owner_id uuid, since timestamptz)
  returns integer
  language sql
  stable
  set search_path = ''
as $$
  with windows as (
    select d.id, greatest(d.registered_at, uptime_percent.since) as start
    from public.devices as d
    where d.owner_id = uptime_percent.owner_id and d.registered_at < now()
  ),
  changes as (
    -- The state each window starts in: the last change before it, else online.
    select
      w.id,
      w.start as at,
      coalesce(
        (
          select e.kind
          from public.events as e
          where e.device_id = w.id
            and e.kind in ('device_offline', 'device_online')
            and e.occurred_at <= w.start
          order by e.occurred_at desc
          limit 1
        ),
        'device_online'
      ) as kind
    from windows as w
    union all
    select e.device_id, e.occurred_at, e.kind
    from public.events as e
    join windows as w on w.id = e.device_id
    where e.kind in ('device_offline', 'device_online') and e.occurred_at > w.start
  ),
  spans as (
    select
      c.kind,
      extract(epoch from lead(c.at, 1, now()) over (partition by c.id order by c.at) - c.at) as seconds
    from changes as c
  )
  select floor(
    100 * coalesce(sum(s.seconds) filter (where s.kind = 'device_online'), 0) / nullif(sum(s.seconds), 0)
  )::integer
  from spans as s
$$;

-- The weekly summary for one owner: their phones and three counts for the
-- last 7 days. Nothing when the owner turned the summary off, has no
-- registered DeKoi, or had a summary in the last 6 days. Calling it records
-- the summary, so a repeated or replayed request sends nothing; the day of
-- margin keeps the weekly job's own timing from skipping a week.
create function public.weekly_summary_v1(user_id uuid)
  returns table (token text, incidents integer, new_devices integer, uptime_percent integer)
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_since timestamptz := now() - interval '7 days';
  v_uptime integer;
  v_incidents integer;
  v_new_devices integer;
begin
  if exists (
    select 1
    from public.owner_settings as s
    where s.user_id = weekly_summary_v1.user_id and not s.weekly_summary
  ) then
    return;
  end if;

  v_uptime := private.uptime_percent(weekly_summary_v1.user_id, v_since);
  if v_uptime is null then
    return;
  end if;

  insert into private.weekly_summaries as w (user_id, sent_at)
  values (weekly_summary_v1.user_id, now())
  on conflict on constraint weekly_summaries_pkey do update
  set sent_at = excluded.sent_at
  where w.sent_at <= excluded.sent_at - interval '6 days';

  if not found then
    return;
  end if;

  v_incidents := private.incident_count(weekly_summary_v1.user_id, v_since);

  select count(distinct e.src_mac)::integer into v_new_devices
  from public.events as e
  join public.devices as d on d.id = e.device_id
  where d.owner_id = weekly_summary_v1.user_id and e.kind = 'new_host' and e.occurred_at >= v_since;

  return query
  select t.token, v_incidents, v_new_devices, v_uptime
  from private.push_tokens as t
  where t.user_id = weekly_summary_v1.user_id;
end;
$$;

revoke all on function public.weekly_summary_v1(uuid) from public, anon, authenticated, service_role;
grant execute on function public.weekly_summary_v1(uuid) to service_role;

-- Posts a body to the notify function with the shared secret, as
-- 20261107150000 describes. Returns the pg_net request id, or null while the
-- URL or the secret is not in Vault (tests, local runs).
create function private.post_to_notify(body jsonb)
  returns bigint
  language plpgsql
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

  return net.http_post(
    url := v_url,
    body := post_to_notify.body,
    headers := pg_catalog.jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Notify-Secret', v_secret
    )
  );
end;
$$;

create or replace function private.post_event_to_notify()
  returns trigger
  language plpgsql
  security definer
  set search_path = ''
as $$
begin
  perform private.post_to_notify(pg_catalog.jsonb_build_object('event_id', new.id));
  return null;
end;
$$;

-- One request per owner with a registered DeKoi; weekly_summary_v1 decides
-- whether it becomes a push. Returns the number of requests queued.
create function private.post_weekly_summaries()
  returns integer
  language sql
  set search_path = ''
as $$
  select count(private.post_to_notify(pg_catalog.jsonb_build_object('weekly_summary_for', o.owner_id)))::integer
  from (select distinct d.owner_id from public.devices as d where d.registered_at is not null) as o
$$;

-- Sundays at 17:00 UTC, early evening in Europe.
select cron.schedule('dekoi-weekly-summary', '0 17 * * 0', 'select private.post_weekly_summaries()');

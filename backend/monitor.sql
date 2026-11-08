-- The daily database checks of docs/security.md, "Monitoring". The monitor
-- workflow runs this through the Management API as supabase_read_only_user
-- (read all data, bypass RLS, no writes). One row per check: a count and
-- the most it may be. Counts only, never a row's contents.
-- $1: the names of the cron jobs that the migrations schedule, a JSON array.
with expected_jobs as (
  select pg_catalog.jsonb_array_elements_text($1::jsonb) as jobname
)
-- 70% of the Free plan's 500 MB.
select
  'database size in MB' as name,
  (pg_catalog.pg_database_size(pg_catalog.current_database()) / 1048576)::integer as value,
  350 as maximum
-- Every event calls notify at most once: 80% of the Free plan's 500,000
-- Edge Function calls a month.
union all
select
  'events received this month',
  (
    select count(*)
    from public.events as e
    where e.received_at >= pg_catalog.date_trunc('month', now())
  )::integer,
  400000
-- Over 600 events in an hour, the notify gate held back everyone's pushes
-- (ADR 0016).
union all
select
  'hours of the last day with the notify gate closed',
  (
    select count(*)
    from (
      select 1
      from public.events as e
      where e.received_at > now() - interval '1 day'
      group by pg_catalog.date_trunc('hour', e.received_at)
      having count(*) > 600
    ) as h
  )::integer,
  0
-- Pushes. Without the URL and the secret in Vault the events trigger posts
-- nothing (private.post_to_notify). pg_net keeps notify's answers for 6
-- hours (pg_net.ttl): anything but 2xx is a wrong secret (401), a missing
-- function (404), an exception such as a refused FCM key (500), the
-- runtime's limits (5xx), or FCM refusing a phone (502). Timeouts carry no
-- status: notify may still have sent the push.
union all
select
  'notify URL and secret missing from Vault',
  (
    select 2 - count(distinct s.name)
    from vault.secrets as s
    where s.name in ('notify_url', 'notify_secret')
  )::integer,
  0
union all
select
  'notify calls of the last 6 hours that failed',
  (
    select count(*)
    from net._http_response as r
    where r.status_code not between 200 and 299
  )::integer,
  0
-- Flooding: accounts over half a limit of ADR 0016.
union all
select
  'accounts with more than 5 of 10 devices',
  (
    select count(*)
    from (select 1 from public.devices as d group by d.owner_id having count(*) > 5) as a
  )::integer,
  0
union all
select
  'accounts with more than 2 of 5 setups waiting',
  (
    select count(*)
    from (
      select 1
      from public.devices as d
      where d.registered_at is null
      group by d.owner_id
      having count(*) > 2
    ) as a
  )::integer,
  0
union all
select
  'accounts with more than 1800 of 3600 events in an hour of the last day',
  (
    select count(distinct a.owner_id)
    from (
      select d.owner_id
      from public.events as e
      join public.devices as d on d.id = e.device_id
      where e.received_at > now() - interval '1 day' and e.kind <> 'device_offline'
      group by d.owner_id, pg_catalog.date_trunc('hour', e.received_at)
      having count(*) > 1800
    ) as a
  )::integer,
  0
union all
select
  'accounts with more than 5 of 10 push tokens',
  (
    select count(*)
    from (select 1 from private.push_tokens as t group by t.user_id having count(*) > 5) as a
  )::integer,
  0
union all
select
  'setups waiting in all',
  (select count(*) from public.devices as d where d.registered_at is null)::integer,
  100
-- Jobs: exactly those of the migrations, each run within its period (a
-- day, or 8 days for a job on given weekdays), and no failed run.
union all
select
  'cron jobs missing or inactive',
  (
    select count(*)
    from expected_jobs as x
    where not exists (select 1 from cron.job as j where j.jobname = x.jobname and j.active)
  )::integer,
  0
union all
select
  'cron jobs that no migration schedules',
  (
    select count(*)
    from cron.job as j
    where not exists (select 1 from expected_jobs as x where x.jobname = j.jobname)
  )::integer,
  0
union all
select
  'cron jobs without a run in their period',
  (
    select count(*)
    from cron.job as j
    where j.active
      and not exists (
        select 1
        from cron.job_run_details as r
        where r.jobid = j.jobid
          and r.start_time > now() - case
            when pg_catalog.split_part(j.schedule, ' ', 5) = '*' then interval '25 hours'
            else interval '8 days'
          end
      )
  )::integer,
  0
-- Runs of the last ten minutes may still be running.
union all
select
  'cron runs of the last day that did not succeed',
  (
    select count(*)
    from cron.job_run_details as r
    where r.start_time between now() - interval '1 day' and now() - interval '10 minutes'
      and r.status <> 'succeeded'
  )::integer,
  0

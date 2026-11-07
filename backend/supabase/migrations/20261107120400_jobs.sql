-- Scheduled jobs. They run as postgres, the owner of these functions.

-- A device silent for 30 minutes gets exactly one device_offline event; its
-- next heartbeat produces device_online (see device_heartbeat_v1).
create function private.mark_offline_devices()
  returns integer
  language sql
  set search_path = ''
as $$
  with went_offline as (
    update public.devices
    set online = false
    where online and last_seen_at < now() - interval '30 minutes'
    returning id
  ),
  inserted as (
    insert into public.events (device_id, occurred_at, kind)
    select id, now(), 'device_offline' from went_offline
    returning 1
  )
  select count(*)::integer from inserted
$$;

create function private.apply_retention()
  returns void
  language sql
  set search_path = ''
as $$
  delete from public.events where occurred_at < now() - interval '90 days';
  delete from public.network_hosts where last_seen_at < now() - interval '90 days';
$$;

create extension if not exists pg_cron;

select cron.schedule('dekoi-mark-offline', '*/5 * * * *', 'select private.mark_offline_devices()');
select cron.schedule('dekoi-retention', '17 3 * * *', 'select private.apply_retention()');

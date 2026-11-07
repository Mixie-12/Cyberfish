-- New devices on the network (ADR 0015). A database trigger, not the agent,
-- decides that a host is new, so agents in the field need no change and no
-- device can report the kind itself.

-- Every event kind: those a device may report and those the backend writes.
create function private.event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select private.device_event_kinds() || array['device_offline', 'device_online', 'new_host']
$$;

alter table public.events drop constraint events_kind_check;
alter table public.events add constraint events_kind_check check (kind = any (private.event_kinds()));

-- As in 20261107160000, plus new_host. Critical: someone tried to get in, or
-- is redirecting traffic on the network (spoofed addresses, a rogue DHCP
-- server, poisoned name lookups). Warning: probing and contact (connections,
-- port and network scans), the device going offline, and IPv6 router
-- advertisements from a new router, which smart home hubs acting as Thread
-- border routers send legitimately. Info: the device came back, and a device
-- joined the network, which is usually a new phone or a guest. IDS alerts
-- follow Suricata's severity: 1 critical, 2 warning, 3 and lower priorities
-- info; an alert without a severity is a warning. A kind missing here yields
-- null and fails the insert, so a new kind cannot be added without deciding
-- its severity.
create or replace function private.event_severity(kind text, detail jsonb)
  returns text
  language sql
  immutable
  set search_path = ''
as $$
  select case
    when kind = 'ids_alert' then
      case
        when pg_catalog.jsonb_typeof(detail -> 'severity') is distinct from 'number' then 'warning'
        when (detail ->> 'severity')::numeric = 1 then 'critical'
        when (detail ->> 'severity')::numeric >= 3 then 'info'
        else 'warning'
      end
    when kind in ('login_attempt', 'arp_spoof', 'rogue_dhcp', 'name_poisoning') then 'critical'
    when kind in ('connection', 'port_scan', 'host_scan', 'rogue_router', 'device_offline') then 'warning'
    when kind in ('device_online', 'new_host') then 'info'
  end
$$;

-- The rows a statement inserted are first sightings: a known address takes
-- the update path of the hosts upsert and is not in this transition table.
-- They are new_host events once the DeKoi's first inventory row is 24 hours
-- old; the hosts of the first day are the household, not newcomers. A host
-- removed by retention after 90 days unseen is new again when it returns.
create function private.record_new_hosts()
  returns trigger
  language plpgsql
  set search_path = ''
as $$
begin
  insert into public.events (device_id, occurred_at, kind, src_ip, src_mac)
  select n.device_id, n.first_seen_at, 'new_host', n.ip, n.mac
  from new_hosts as n
  where exists (
    select 1
    from public.network_hosts as h
    where h.device_id = n.device_id
      and h.first_seen_at <= now() - interval '24 hours'
  );
  return null;
end;
$$;

create trigger network_hosts_record_new
  after insert on public.network_hosts
  referencing new table as new_hosts
  for each statement
  execute function private.record_new_hosts();

-- As in 20261107150000, with one rule added: new_host notifies at most once
-- per device per 10 minutes, whatever its source. New devices tend to arrive
-- together (a guest's phone and watch, a DeKoi moved to another network);
-- one push says so and the app lists them all. Returns the owner's push
-- tokens and the template key (the event kind) when the event should
-- notify, otherwise no rows. Rules: device_online never; device_offline
-- always; a source the owner marked as trusted never; other events at most
-- once per device and source (src_ip, else src_mac) per 10 minutes. Calling
-- it records the push, so a repeated call for the same event returns nothing.
create or replace function public.notification_targets_v1(event_id bigint)
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
      case
        when v_event.kind = 'new_host' then 'new_host'
        else coalesce(pg_catalog.host(v_event.src_ip), v_event.src_mac::text, '')
      end,
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

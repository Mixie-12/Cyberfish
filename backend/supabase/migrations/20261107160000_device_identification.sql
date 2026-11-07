-- Device identification, networks and event severity (ADR 0010). Additive for
-- devices in the field: device_hosts_v1 is unchanged and its hosts get the
-- defaults below.

-- A service a host announces, for example "airplay" or "ipp".
create domain private.service_label as text not null
  check (value ~ '^[a-z0-9-]{1,32}$');

-- Host names and models are attacker-controlled: bounded, no control
-- characters, shown as plain text only. Kind and os come from fixed
-- vocabularies, so they may appear in notifications.
alter table public.network_hosts
  add column hostname text check (char_length(hostname) between 1 and 64 and hostname !~ '[[:cntrl:]]'),
  add column model text check (char_length(model) between 1 and 64 and model !~ '[[:cntrl:]]'),
  add column kind text not null default 'unknown' check (
    kind in (
      'router', 'switch', 'access_point', 'computer', 'phone', 'tablet', 'tv', 'media_player',
      'speaker', 'game_console', 'printer', 'camera', 'storage', 'smart_home', 'wearable',
      'server', 'unknown'
    )
  ),
  add column os text check (
    os in (
      'android', 'ios', 'ipados', 'macos', 'windows', 'linux', 'chromeos', 'tvos', 'webos',
      'tizen', 'fire_os', 'other'
    )
  ),
  add column services private.service_label[] not null default '{}' check (cardinality(services) <= 16);

-- Networks the device can see: its own subnets and those it has evidence of.
create table public.networks (
  device_id uuid not null references public.devices (id) on delete cascade,
  cidr cidr not null,
  source text not null check (source in ('interface', 'router_advertisement', 'dhcp_route', 'observed')),
  -- The router serving this network as seen from the device, when known.
  gateway inet,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (device_id, cidr)
);

alter table public.networks enable row level security;

create policy networks_select_own on public.networks
  for select to authenticated
  using (device_id in (select id from public.devices));

grant select on public.networks to authenticated;

-- How serious an event is, for the app and the notify function; neither keeps
-- its own mapping. Critical: someone tried to get in, or is redirecting
-- traffic on the network (spoofed addresses, a rogue DHCP server, poisoned
-- name lookups). Warning: probing and contact (connections, port and network
-- scans), the device going offline, and IPv6 router advertisements from a new
-- router, which smart home hubs acting as Thread border routers send
-- legitimately. Info: the device came back. IDS alerts follow Suricata's
-- severity: 1 critical, 2 warning, 3 and lower priorities info; an alert
-- without a severity is a warning. A kind missing here yields null and fails
-- the insert, so a new kind cannot be added without deciding its severity.
create function private.event_severity(kind text, detail jsonb)
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
    when kind = 'device_online' then 'info'
  end
$$;

alter table public.events
  add column severity text not null
    generated always as (private.event_severity(kind, detail)) stored
    check (severity in ('critical', 'warning', 'info'));

-- Upserts the hosts and networks the device sees, up to 1024 hosts and 64
-- networks per call; returns the number of rows written. Identification
-- fields a report leaves out (null, kind "unknown", no services) keep their
-- stored value: evidence arrives at different times and an agent restart
-- forgets it, which must not erase what is already known.
create function public.device_hosts_v2(device_id uuid, secret text, hosts jsonb, networks jsonb)
  returns integer
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_hosts integer;
  v_networks integer;
begin
  perform private.authenticate_device(device_hosts_v2.device_id, device_hosts_v2.secret);

  if pg_catalog.jsonb_typeof(device_hosts_v2.hosts) is distinct from 'array'
    or pg_catalog.jsonb_typeof(device_hosts_v2.networks) is distinct from 'array'
    or pg_catalog.jsonb_array_length(device_hosts_v2.hosts) > 1024
    or pg_catalog.jsonb_array_length(device_hosts_v2.networks) > 64
    -- A repeated key would fail the upsert with 21000, which is HTTP 500,
    -- and the agent would resend the same report forever.
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(device_hosts_v2.hosts) as h (mac macaddr)
      group by h.mac
      having count(*) > 1
    )
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(device_hosts_v2.networks) as x (cidr cidr)
      group by x.cidr
      having count(*) > 1
    )
  then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  insert into public.network_hosts as n (
    device_id, mac, ip, vendor, hostname, model, kind, os, services
  )
  select
    device_hosts_v2.device_id, h.mac, h.ip, h.vendor, h.hostname, h.model,
    coalesce(h.kind, 'unknown'), h.os, coalesce(h.services, '{}')
  from pg_catalog.jsonb_to_recordset(device_hosts_v2.hosts) as h (
    mac macaddr, ip inet, vendor text, hostname text, model text, kind text, os text,
    services private.service_label[]
  )
  on conflict on constraint network_hosts_pkey do update
  set ip = excluded.ip,
      vendor = coalesce(excluded.vendor, n.vendor),
      hostname = coalesce(excluded.hostname, n.hostname),
      model = coalesce(excluded.model, n.model),
      kind = coalesce(nullif(excluded.kind, 'unknown'), n.kind),
      os = coalesce(excluded.os, n.os),
      services = coalesce(nullif(excluded.services, '{}'), n.services),
      last_seen_at = now();

  get diagnostics v_hosts = row_count;

  insert into public.networks as w (device_id, cidr, source, gateway)
  select device_hosts_v2.device_id, x.cidr, x.source, x.gateway
  from pg_catalog.jsonb_to_recordset(device_hosts_v2.networks) as x (cidr cidr, source text, gateway inet)
  on conflict on constraint networks_pkey do update
  set source = excluded.source,
      gateway = excluded.gateway,
      last_seen_at = now();

  get diagnostics v_networks = row_count;
  return v_hosts + v_networks;
end;
$$;

-- The default privileges (20261107120000) already withhold both functions;
-- revoking here keeps that true even if those defaults ever change.
revoke all on function private.event_severity(text, jsonb) from public, anon, authenticated, service_role;
revoke all on function public.device_hosts_v2(uuid, text, jsonb, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.device_hosts_v2(uuid, text, jsonb, jsonb) to anon;

create or replace function private.apply_retention()
  returns void
  language sql
  set search_path = ''
as $$
  delete from public.events where occurred_at < now() - interval '90 days';
  delete from public.network_hosts where last_seen_at < now() - interval '90 days';
  delete from public.networks where last_seen_at < now() - interval '90 days';
  delete from private.notified_sources where notified_at < now() - interval '1 day';
$$;

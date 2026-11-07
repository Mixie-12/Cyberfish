-- Network health, exposure checks and the speed test (ADR 0015). Additive
-- for devices in the field: device_health_v1 is new and no existing device
-- function changes.

-- The latest state of each DeKoi's network. public_ip and public_country are
-- taken from the request by device_health_v1, never from the device.
-- speed_test is the owner's setting, off by default.
alter table public.devices
  add column speed_test boolean not null default false,
  add column public_ip inet,
  add column public_country text check (public_country ~ '^[A-Z]{2}$'),
  add column dns_check text check (dns_check in ('ok', 'mismatch', 'unavailable')),
  add column dns_resolver inet,
  add column dns_checked_at timestamptz,
  add column forwards_checked_at timestamptz;

grant update (speed_test) on public.devices to authenticated;

-- Hourly aggregates of the probes a device sends once a minute: the gateway
-- by ICMP echo, the internet by a TCP connection to the backend, the LAN
-- resolver by one lookup. Latencies are of the probes that were answered;
-- outage minutes are failed probes in a run of at least two.
create table public.health_samples (
  device_id uuid not null references public.devices (id) on delete cascade,
  hour timestamptz not null check (date_trunc('hour', hour, 'UTC') = hour),
  target text not null check (target in ('gateway', 'internet', 'resolver')),
  probes smallint not null check (probes between 1 and 60),
  lost smallint not null check (lost between 0 and probes),
  median_ms real check (median_ms between 0 and 60000),
  worst_ms real check (worst_ms between median_ms and 60000),
  outage_minutes smallint not null check (outage_minutes between 0 and lost),
  primary key (device_id, hour, target),
  check ((median_ms is null) = (lost = probes) and (worst_ms is null) = (median_ms is null))
);

-- Port mappings the gateway reports through UPnP IGD. open is false for a
-- mapping missing from the latest read. Rows stay for 90 days, so a mapping
-- that an application adds and removes again is reported as new only once.
create table public.port_forwards (
  device_id uuid not null references public.devices (id) on delete cascade,
  protocol text not null check (protocol in ('tcp', 'udp')),
  -- 0 is the IGD wildcard: every port.
  external_port integer not null check (external_port between 0 and 65535),
  internal_client inet not null check (family(internal_client) = 4 and masklen(internal_client) = 32),
  internal_port integer not null check (internal_port between 1 and 65535),
  -- Any device on the network can set this text when it asks for a mapping.
  description text check (char_length(description) between 1 and 64 and description !~ '[[:cntrl:]]'),
  open boolean not null default true,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (device_id, protocol, external_port, internal_client)
);

create table public.speed_tests (
  device_id uuid not null references public.devices (id) on delete cascade,
  tested_at timestamptz not null default now(),
  download_mbps numeric(7, 1) not null check (download_mbps between 0 and 100000),
  primary key (device_id, tested_at)
);

alter table public.health_samples enable row level security;
alter table public.port_forwards enable row level security;
alter table public.speed_tests enable row level security;

create policy health_samples_select_own on public.health_samples
  for select to authenticated
  using (device_id in (select id from public.devices));

create policy port_forwards_select_own on public.port_forwards
  for select to authenticated
  using (device_id in (select id from public.devices));

create policy speed_tests_select_own on public.speed_tests
  for select to authenticated
  using (device_id in (select id from public.devices));

grant select on public.health_samples, public.port_forwards, public.speed_tests to authenticated;

-- Kinds the backend writes itself from what device_health_v1 reports; a
-- device cannot send them through device_events_v1.
alter table public.events drop constraint events_kind_check;
alter table public.events add constraint events_kind_check check (
  kind = any (
    private.device_event_kinds() || array['device_offline', 'device_online', 'dns_hijack', 'port_forward']
  )
);

-- As in 20261107160000_device_identification.sql, plus the two kinds above.
-- Critical: someone tried to get in, or is redirecting traffic on the
-- network (spoofed addresses, a rogue DHCP server, poisoned name lookups,
-- name lookups answered with other addresses than the real ones). Warning:
-- probing and contact (connections, port and network scans), the device
-- going offline, IPv6 router advertisements from a new router, which smart
-- home hubs acting as Thread border routers send legitimately, and a new
-- port opened to the internet through UPnP, which games consoles and
-- cameras do legitimately. Info: the device came back. IDS alerts follow
-- Suricata's severity: 1 critical, 2 warning, 3 and lower priorities info;
-- an alert without a severity is a warning. A kind missing here yields null
-- and fails the insert, so a new kind cannot be added without deciding its
-- severity.
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
    when kind in ('login_attempt', 'arp_spoof', 'rogue_dhcp', 'name_poisoning', 'dns_hijack') then 'critical'
    when kind in (
      'connection', 'port_scan', 'host_scan', 'rogue_router', 'device_offline', 'port_forward'
    ) then 'warning'
    when kind = 'device_online' then 'info'
  end
$$;

-- The hourly report of a device's network checks (ADR 0015):
-- - samples: up to 216 hourly aggregates (72 hours of three targets) from
--   the last 7 days; a resent hour replaces the stored one.
-- - dns, resolver: the latest integrity result against the LAN resolver:
--   'ok', 'mismatch' (only after two checks in a row disagreed) or
--   'unavailable'; null when nothing was concluded since the last report.
--   A change to 'mismatch' is a dns_hijack event.
-- - forwards: every enabled port mapping the gateway reported, up to 64, or
--   null when it could not be read. A mapping not seen before is a
--   port_forward event.
-- - download_mbps: a speed test result; stored only while the owner has the
--   test on, and at most one per 20 hours.
-- The public address is cf-connecting-ip, which Cloudflare in front of the
-- Supabase API sets to the address it saw, or else the first entry of
-- X-Forwarded-For, the header Supabase documents for the client address
-- (docs/guides/api/securing-your-api). A client can put its own value first
-- in X-Forwarded-For; the only client here holds the device secret and
-- could report anything anyway, and the address is only shown to the
-- owner. The country is cf-ipcountry when the platform passes it on: XX
-- when unknown, T1 for Tor. No header is ever looked up elsewhere.
-- Returns {"speed_test": true} while the owner has the test on and no result
-- is stored for the last 20 hours.
create function public.device_health_v1(
  device_id uuid,
  secret text,
  samples jsonb,
  dns text,
  resolver inet,
  forwards jsonb,
  download_mbps numeric
)
  returns jsonb
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_forwards jsonb := nullif(device_health_v1.forwards, 'null'::jsonb);
  v_headers jsonb := coalesce(nullif(pg_catalog.current_setting('request.headers', true), ''), '{}')::jsonb;
  v_ip text := pg_catalog.btrim(coalesce(
    v_headers ->> 'cf-connecting-ip',
    pg_catalog.split_part(v_headers ->> 'x-forwarded-for', ',', 1)
  ));
  v_country text := v_headers ->> 'cf-ipcountry';
  v_previous_dns text;
  v_speed_test boolean;
  v_tested_recently boolean;
begin
  perform private.authenticate_device(device_health_v1.device_id, device_health_v1.secret);

  if pg_catalog.jsonb_typeof(device_health_v1.samples) is distinct from 'array'
    or pg_catalog.jsonb_array_length(device_health_v1.samples) > 216
    or pg_catalog.jsonb_typeof(coalesce(v_forwards, '[]')) <> 'array'
    or pg_catalog.jsonb_array_length(coalesce(v_forwards, '[]')) > 64
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(device_health_v1.samples) as s (hour timestamptz)
      where s.hour is null or s.hour not between now() - interval '7 days' and now()
    )
    -- A repeated key would fail the upsert with 21000, which is HTTP 500,
    -- and the agent would resend the same report forever.
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(device_health_v1.samples) as s (hour timestamptz, target text)
      group by s.hour, s.target
      having count(*) > 1
    )
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(coalesce(v_forwards, '[]'))
        as f (protocol text, external_port integer, internal_client inet)
      group by f.protocol, f.external_port, f.internal_client
      having count(*) > 1
    )
  then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  select d.dns_check, d.speed_test into v_previous_dns, v_speed_test
  from public.devices as d
  where d.id = device_health_v1.device_id
  for update;

  update public.devices as d
  set public_ip = case
        when v_ip ~ '^[0-9A-Fa-f.:]{2,45}$' and pg_catalog.pg_input_is_valid(v_ip, 'inet') then v_ip::inet
      end,
      public_country = case when v_country ~ '^[A-Z]{2}$' and v_country <> 'XX' then v_country end,
      dns_check = coalesce(device_health_v1.dns, d.dns_check),
      dns_resolver = case when device_health_v1.dns is null then d.dns_resolver else device_health_v1.resolver end,
      dns_checked_at = case when device_health_v1.dns is null then d.dns_checked_at else now() end,
      forwards_checked_at = case when v_forwards is null then d.forwards_checked_at else now() end
  where d.id = device_health_v1.device_id;

  -- No src_mac: an owner who marked the router as known must still hear
  -- that its answers are being changed.
  if device_health_v1.dns = 'mismatch' and v_previous_dns is distinct from 'mismatch' then
    insert into public.events (device_id, occurred_at, kind, src_ip)
    values (device_health_v1.device_id, now(), 'dns_hijack', device_health_v1.resolver);
  end if;

  insert into public.health_samples as h (
    device_id, hour, target, probes, lost, median_ms, worst_ms, outage_minutes
  )
  select
    device_health_v1.device_id, s.hour, s.target, s.probes, s.lost, s.median_ms, s.worst_ms,
    s.outage_minutes
  from pg_catalog.jsonb_to_recordset(device_health_v1.samples) as s (
    hour timestamptz, target text, probes smallint, lost smallint, median_ms real, worst_ms real,
    outage_minutes smallint
  )
  on conflict on constraint health_samples_pkey do update
  set probes = excluded.probes,
      lost = excluded.lost,
      median_ms = excluded.median_ms,
      worst_ms = excluded.worst_ms,
      outage_minutes = excluded.outage_minutes;

  if v_forwards is not null then
    -- The source is the device the port leads to, as the inventory knows it.
    with added as (
      insert into public.port_forwards (
        device_id, protocol, external_port, internal_client, internal_port, description
      )
      select
        device_health_v1.device_id, f.protocol, f.external_port, f.internal_client,
        f.internal_port, f.description
      from pg_catalog.jsonb_to_recordset(v_forwards) as f (
        protocol text, external_port integer, internal_client inet, internal_port integer,
        description text
      )
      on conflict on constraint port_forwards_pkey do nothing
      returning protocol, external_port, internal_client
    )
    insert into public.events (device_id, occurred_at, kind, src_ip, src_mac, dst_port, detail)
    select
      device_health_v1.device_id, now(), 'port_forward', a.internal_client,
      (
        select h.mac
        from public.network_hosts as h
        where h.device_id = device_health_v1.device_id and h.ip = a.internal_client
        order by h.last_seen_at desc
        limit 1
      ),
      a.external_port, pg_catalog.jsonb_build_object('protocol', a.protocol)
    from added as a;

    update public.port_forwards as p
    set internal_port = f.internal_port,
        description = f.description,
        open = true,
        last_seen_at = now()
    from pg_catalog.jsonb_to_recordset(v_forwards) as f (
      protocol text, external_port integer, internal_client inet, internal_port integer,
      description text
    )
    where p.device_id = device_health_v1.device_id
      and (p.protocol, p.external_port, p.internal_client)
        = (f.protocol, f.external_port, f.internal_client);

    update public.port_forwards as p
    set open = false
    where p.device_id = device_health_v1.device_id
      and p.open
      and not exists (
        select 1
        from pg_catalog.jsonb_to_recordset(v_forwards)
          as f (protocol text, external_port integer, internal_client inet)
        where (f.protocol, f.external_port, f.internal_client)
          = (p.protocol, p.external_port, p.internal_client)
      );
  end if;

  v_tested_recently := exists (
    select 1
    from public.speed_tests as t
    where t.device_id = device_health_v1.device_id and t.tested_at > now() - interval '20 hours'
  );

  if device_health_v1.download_mbps is not null and v_speed_test and not v_tested_recently then
    insert into public.speed_tests (device_id, download_mbps)
    values (device_health_v1.device_id, device_health_v1.download_mbps);
    v_tested_recently := true;
  end if;

  return pg_catalog.jsonb_build_object('speed_test', v_speed_test and not v_tested_recently);
end;
$$;

-- The default privileges (20261107120000) already withhold the function;
-- revoking here keeps that true even if those defaults ever change.
revoke all on function public.device_health_v1(uuid, text, jsonb, text, inet, jsonb, numeric)
  from public, anon, authenticated, service_role;
grant execute on function public.device_health_v1(uuid, text, jsonb, text, inet, jsonb, numeric) to anon;

create or replace function private.apply_retention()
  returns void
  language sql
  set search_path = ''
as $$
  delete from public.events where occurred_at < now() - interval '90 days';
  delete from public.network_hosts where last_seen_at < now() - interval '90 days';
  delete from public.networks where last_seen_at < now() - interval '90 days';
  delete from public.health_samples where hour < now() - interval '90 days';
  delete from public.port_forwards where last_seen_at < now() - interval '90 days';
  delete from public.speed_tests where tested_at < now() - interval '90 days';
  delete from private.notified_sources where notified_at < now() - interval '1 day';
$$;

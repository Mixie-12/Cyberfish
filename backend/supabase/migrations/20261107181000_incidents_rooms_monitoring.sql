-- Read state, names and rooms, monitoring status (ADR 0013). Additive for
-- devices in the field: device_heartbeat_v1 is unchanged and leaves the
-- monitoring columns as they are.

-- When the owner first opened the event in the app; null until then.
alter table public.events add column seen_at timestamptz;

-- Marks the caller's events as seen, at most 500 per call. Events of other
-- owners' devices are skipped, and an event keeps the time it was first
-- seen. Returns the number of events newly marked.
create function public.acknowledge_events_v1(event_ids bigint[])
  returns integer
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_marked integer;
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  if acknowledge_events_v1.event_ids is null
    or pg_catalog.cardinality(acknowledge_events_v1.event_ids) > 500
  then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  update public.events as e
  set seen_at = now()
  from public.devices as d
  where e.id = any (acknowledge_events_v1.event_ids)
    and e.seen_at is null
    and d.id = e.device_id
    and d.owner_id = auth.uid();

  get diagnostics v_marked = row_count;
  return v_marked;
end;
$$;

-- The owner's name for a host and the room it is in. Owner text: bounded,
-- no control characters, shown as plain text and never put into a
-- notification. Written through the existing update policy, like trusted.
alter table public.network_hosts
  add column label text check (char_length(label) between 1 and 40 and label !~ '[[:cntrl:]]'),
  add column room text check (char_length(room) between 1 and 32 and room !~ '[[:cntrl:]]');

grant update (label, room) on public.network_hosts to authenticated;

-- What the agent watches and how each watcher is doing: up to 16 monitors,
-- each named once from a fixed list, with a state and a detail that holds
-- only small counts and a date. Nothing in it is free text.
--
-- pg_input_is_valid is stable, not immutable; with the format fixed by the
-- pattern first (no time zone, no locale-dependent spelling) its answer
-- cannot change with session settings.
create function private.valid_monitors(monitors jsonb)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select pg_catalog.jsonb_typeof(monitors) = 'array'
    and pg_catalog.jsonb_array_length(monitors) <= 16
    and not exists (
      select 1
      from pg_catalog.jsonb_array_elements(monitors) as m (monitor)
      where case
        when pg_catalog.jsonb_typeof(m.monitor) <> 'object' then true
        else m.monitor - array['name', 'state', 'detail'] <> '{}'::jsonb
          or pg_catalog.jsonb_typeof(m.monitor -> 'name') is distinct from 'string'
          or (m.monitor ->> 'name') not in (
            'decoys', 'port_scan', 'arp_watch', 'dhcp_watch', 'router_watch',
            'name_poisoning', 'ids', 'inventory'
          )
          or pg_catalog.jsonb_typeof(m.monitor -> 'state') is distinct from 'string'
          or (m.monitor ->> 'state') not in ('ok', 'starting', 'off', 'failed')
          or pg_catalog.jsonb_typeof(coalesce(m.monitor -> 'detail', '{}')) <> 'object'
          or exists (
            select 1
            from pg_catalog.jsonb_each(m.monitor -> 'detail') as d (key, value)
            where case
              when d.key in ('rules', 'hosts', 'decoys') then
                pg_catalog.jsonb_typeof(d.value) <> 'number'
                or (d.value #>> '{}') !~ '^[0-9]{1,7}$'
              when d.key = 'ruleset_date' then
                pg_catalog.jsonb_typeof(d.value) <> 'string'
                or (d.value #>> '{}') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                or not pg_catalog.pg_input_is_valid(d.value #>> '{}', 'date')
              else true
            end
          )
      end
    )
    and (
      select count(distinct m.monitor ->> 'name')
      from pg_catalog.jsonb_array_elements(monitors) as m (monitor)
    ) = pg_catalog.jsonb_array_length(monitors)
$$;

-- The images the device runs: up to 8, each named once, with its release
-- version and its build time in UTC to the second.
create function private.valid_images(images jsonb)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select pg_catalog.jsonb_typeof(images) = 'array'
    and pg_catalog.jsonb_array_length(images) <= 8
    and not exists (
      select 1
      from pg_catalog.jsonb_array_elements(images) as i (image)
      where case
        when pg_catalog.jsonb_typeof(i.image) <> 'object' then true
        else i.image - array['name', 'version', 'updated_at'] <> '{}'::jsonb
          or pg_catalog.jsonb_typeof(i.image -> 'name') is distinct from 'string'
          or (i.image ->> 'name') not in ('canary', 'agent', 'ids')
          or pg_catalog.jsonb_typeof(i.image -> 'version') is distinct from 'string'
          or (i.image ->> 'version') !~ '^[0-9A-Za-z.+-]{1,32}$'
          or pg_catalog.jsonb_typeof(i.image -> 'updated_at') is distinct from 'string'
          or (i.image ->> 'updated_at') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
          or not pg_catalog.pg_input_is_valid(i.image ->> 'updated_at', 'timestamptz')
      end
    )
    and (
      select count(distinct i.image ->> 'name')
      from pg_catalog.jsonb_array_elements(images) as i (image)
    ) = pg_catalog.jsonb_array_length(images)
$$;

-- Domains rather than table checks: a check constraint runs on every update
-- of the row, so an owner renaming a device would need execute on these
-- functions. A domain is checked only when a value is assigned.
create domain private.device_monitors as jsonb check (private.valid_monitors(value));
create domain private.device_images as jsonb check (private.valid_images(value));

-- Empty until the device runs an agent that reports them (ADR 0013).
alter table public.devices
  add column monitors private.device_monitors not null default '[]',
  add column images private.device_images not null default '[]';

-- device_heartbeat_v1 with the monitoring report. Field formats are enforced
-- by the column types, as in v1.
create function public.device_heartbeat_v2(
  device_id uuid,
  secret text,
  agent_version text,
  services text[],
  local_ip inet,
  monitors jsonb,
  images jsonb
)
  returns void
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_was_online boolean;
begin
  perform private.authenticate_device(device_heartbeat_v2.device_id, device_heartbeat_v2.secret);

  select d.online into v_was_online
  from public.devices as d
  where d.id = device_heartbeat_v2.device_id
  for update;

  update public.devices as d
  set last_seen_at = now(),
      online = true,
      agent_version = device_heartbeat_v2.agent_version,
      services = device_heartbeat_v2.services,
      local_ip = device_heartbeat_v2.local_ip,
      monitors = device_heartbeat_v2.monitors,
      images = device_heartbeat_v2.images
  where d.id = device_heartbeat_v2.device_id;

  if not v_was_online then
    insert into public.events (device_id, occurred_at, kind)
    values (device_heartbeat_v2.device_id, now(), 'device_online');
  end if;
end;
$$;

-- The default privileges (20261107120000) already withhold all four;
-- revoking here keeps that true even if those defaults ever change.
revoke all on function private.valid_monitors(jsonb) from public, anon, authenticated, service_role;
revoke all on function private.valid_images(jsonb) from public, anon, authenticated, service_role;
revoke all on function public.acknowledge_events_v1(bigint[])
  from public, anon, authenticated, service_role;
revoke all on function public.device_heartbeat_v2(uuid, text, text, text[], inet, jsonb, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.acknowledge_events_v1(bigint[]) to authenticated;
grant execute on function public.device_heartbeat_v2(uuid, text, text, text[], inet, jsonb, jsonb) to anon;

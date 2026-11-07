-- Network detection beyond the decoys (ADR 0003). Additive for devices in the
-- field: device_events_v1 keeps its signature and accepts more kinds and
-- detail fields; nothing an existing agent sends changes meaning.

-- The one list of event kinds a device may report. The events table and
-- device_events_v1 both use it.
create function private.device_event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select array[
    'login_attempt', 'connection', 'port_scan',
    'host_scan', 'arp_spoof', 'rogue_dhcp', 'rogue_router', 'name_poisoning', 'ids_alert'
  ]
$$;

alter table public.events drop constraint events_kind_check;
alter table public.events add constraint events_kind_check check (
  kind = any (private.device_event_kinds() || array['device_offline', 'device_online'])
);

-- Event details are attacker-influenced. Strings are bounded and free of
-- control characters, numbers are small non-negative integers, and the only
-- array is a list of port numbers.
create or replace function private.valid_detail(detail jsonb)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select pg_catalog.jsonb_typeof(detail) = 'object'
    and pg_catalog.pg_column_size(detail) <= 2048
    and not exists (
      select 1
      from pg_catalog.jsonb_each(detail) as d (key, value)
      where case
        when d.key in (
          'username', 'path', 'user_agent', 'client_version', 'command',
          'claimed_ip', 'previous_mac', 'server_ip', 'protocol', 'signature', 'category'
        ) then
          pg_catalog.jsonb_typeof(d.value) <> 'string'
          or pg_catalog.char_length(d.value #>> '{}') > 256
          or (d.value #>> '{}') ~ '[[:cntrl:]]'
        when d.key in ('targets', 'severity', 'signature_id') then
          pg_catalog.jsonb_typeof(d.value) <> 'number'
          or (d.value #>> '{}') !~ '^[0-9]{1,10}$'
        when d.key = 'ports' then
          pg_catalog.jsonb_typeof(d.value) <> 'array'
          or pg_catalog.jsonb_array_length(d.value) > 64
          or exists (
            select 1
            from pg_catalog.jsonb_array_elements(d.value) as p (port)
            where pg_catalog.jsonb_typeof(p.port) <> 'number'
          )
        else true
      end
    )
$$;

create or replace function public.device_events_v1(device_id uuid, secret text, events jsonb)
  returns integer
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_inserted integer;
begin
  perform private.authenticate_device(device_events_v1.device_id, device_events_v1.secret);

  if pg_catalog.jsonb_typeof(device_events_v1.events) is distinct from 'array'
    or pg_catalog.jsonb_array_length(device_events_v1.events) not between 1 and 200
    or exists (
      select 1
      from pg_catalog.jsonb_to_recordset(device_events_v1.events)
        as e (agent_event_id bigint, occurred_at timestamptz, kind text)
      where e.agent_event_id is null
        or e.kind is null
        or not (e.kind = any (private.device_event_kinds()))
        or e.occurred_at is null
        or e.occurred_at not between now() - interval '30 days' and now() + interval '5 minutes'
    )
  then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  insert into public.events (
    device_id, agent_event_id, occurred_at, kind, service,
    src_ip, src_mac, dst_port, count, detail
  )
  select
    device_events_v1.device_id, e.agent_event_id, e.occurred_at, e.kind, e.service,
    e.src_ip, e.src_mac, e.dst_port, coalesce(e.count, 1), coalesce(e.detail, '{}')
  from pg_catalog.jsonb_to_recordset(device_events_v1.events) as e (
    agent_event_id bigint, occurred_at timestamptz, kind text, service text,
    src_ip inet, src_mac macaddr, dst_port integer, count integer, detail jsonb
  )
  on conflict on constraint events_device_id_agent_event_id_key do nothing;

  get diagnostics v_inserted = row_count;
  return v_inserted;
end;
$$;

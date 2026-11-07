-- Device API, version 1. The agent calls these with the publishable (anon)
-- key and its own secret. This is an ABI for devices in the field: never
-- change the signature or behaviour of a _v1 function; add a _v2 instead.
--
-- Errors: 22023 or 23514 (HTTP 400) for malformed input, which the agent must
-- not retry; 28000 (HTTP 403) for refused credentials. Field formats are
-- enforced by the table constraints, so they are not repeated here.

create function private.sha256_text(value text)
  returns bytea
  language sql
  immutable
  strict
  set search_path = ''
as $$
  select pg_catalog.sha256(pg_catalog.convert_to(value, 'UTF8'))
$$;

-- 26 Crockford base32 characters in canonical upper case: 128 random bits.
create function private.is_claim_code(value text)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select coalesce(value ~ '^[0-9A-HJKMNP-TV-Z]{26}$', false)
$$;

-- 64 lower-case hex characters: 256 random bits generated on the device.
create function private.is_device_secret(value text)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select coalesce(value ~ '^[0-9a-f]{64}$', false)
$$;

create function private.authenticate_device(device_id uuid, secret text)
  returns void
  language plpgsql
  stable
  set search_path = ''
as $$
begin
  if not private.is_device_secret(authenticate_device.secret) or not exists (
    select 1
    from private.device_credentials as c
    where c.device_id = authenticate_device.device_id
      and c.secret_hash = private.sha256_text(authenticate_device.secret)
  ) then
    raise exception 'device authentication failed' using errcode = '28000';
  end if;
end;
$$;

-- Succeeds only for a code that has been claimed in the app. Until then the
-- agent retries. Repeating a successful call with the same secret returns the
-- same device id, so a lost response is harmless.
create function public.device_register_v1(claim_code text, secret text, agent_version text)
  returns uuid
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_code_hash bytea := private.sha256_text(device_register_v1.claim_code);
  v_secret_hash bytea := private.sha256_text(device_register_v1.secret);
  v_device_id uuid;
begin
  if not (
    private.is_claim_code(device_register_v1.claim_code)
    and private.is_device_secret(device_register_v1.secret)
  ) then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  update private.device_credentials as c
  set secret_hash = v_secret_hash
  where c.claim_code_hash = v_code_hash and c.secret_hash is null
  returning c.device_id into v_device_id;

  if v_device_id is null then
    select c.device_id into v_device_id
    from private.device_credentials as c
    where c.claim_code_hash = v_code_hash and c.secret_hash = v_secret_hash;
  end if;

  if v_device_id is null then
    raise exception 'registration refused' using errcode = '28000';
  end if;

  update public.devices as d
  set registered_at = coalesce(d.registered_at, now()),
      agent_version = device_register_v1.agent_version
  where d.id = v_device_id;

  return v_device_id;
end;
$$;

create function public.device_heartbeat_v1(
  device_id uuid,
  secret text,
  agent_version text,
  services text[],
  local_ip inet
)
  returns void
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_was_online boolean;
begin
  perform private.authenticate_device(device_heartbeat_v1.device_id, device_heartbeat_v1.secret);

  select d.online into v_was_online
  from public.devices as d
  where d.id = device_heartbeat_v1.device_id
  for update;

  update public.devices as d
  set last_seen_at = now(),
      online = true,
      agent_version = device_heartbeat_v1.agent_version,
      services = device_heartbeat_v1.services,
      local_ip = device_heartbeat_v1.local_ip
  where d.id = device_heartbeat_v1.device_id;

  if not v_was_online then
    insert into public.events (device_id, occurred_at, kind)
    values (device_heartbeat_v1.device_id, now(), 'device_online');
  end if;
end;
$$;

-- Accepts up to 200 events. Each needs a device-unique agent_event_id, so a
-- resent batch inserts nothing twice. Returns the number of new rows.
create function public.device_events_v1(device_id uuid, secret text, events jsonb)
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
        or e.kind not in ('login_attempt', 'connection', 'port_scan')
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

-- Upserts the hosts seen on the local network, up to 1024 per call.
create function public.device_hosts_v1(device_id uuid, secret text, hosts jsonb)
  returns integer
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_written integer;
begin
  perform private.authenticate_device(device_hosts_v1.device_id, device_hosts_v1.secret);

  if pg_catalog.jsonb_typeof(device_hosts_v1.hosts) is distinct from 'array'
    or pg_catalog.jsonb_array_length(device_hosts_v1.hosts) > 1024
  then
    raise exception 'invalid argument' using errcode = '22023';
  end if;

  insert into public.network_hosts (device_id, mac, ip, vendor)
  select device_hosts_v1.device_id, h.mac, h.ip, h.vendor
  from pg_catalog.jsonb_to_recordset(device_hosts_v1.hosts) as h (mac macaddr, ip inet, vendor text)
  on conflict on constraint network_hosts_pkey do update
  set ip = excluded.ip,
      vendor = excluded.vendor,
      last_seen_at = now();

  get diagnostics v_written = row_count;
  return v_written;
end;
$$;

grant execute on function public.device_register_v1(text, text, text) to anon;
grant execute on function public.device_heartbeat_v1(uuid, text, text, text[], inet) to anon;
grant execute on function public.device_events_v1(uuid, text, jsonb) to anon;
grant execute on function public.device_hosts_v1(uuid, text, jsonb) to anon;

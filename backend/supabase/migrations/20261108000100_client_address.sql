-- device_health_v1 (20261107183000) took the public address from
-- cf-connecting-ip, else from the first X-Forwarded-For entry, which is
-- whatever the caller sent. private.client_ip() (20261108000000) is the one
-- place that reads the client address, from the header the platform sets;
-- the request limits count by it. device_health_v1 now uses it too, so a
-- forged header can no longer set the address the owner sees. Signature,
-- arguments and reply are unchanged; the country stays cf-ipcountry.

create or replace function public.device_health_v1(
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
  set public_ip = private.client_ip(),
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

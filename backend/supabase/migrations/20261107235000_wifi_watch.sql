-- Wi-Fi watch (ADR 0019). Additive for devices in the field: no existing
-- function changes its signature, and the new event kinds are only sent by
-- agents that know them.

-- The home network's name, set by the owner in the app, and the state of the
-- watch as the device last reported it. The name is owner-written text: up to
-- 32 bytes like an SSID, no control characters, never put into a
-- notification.
alter table public.devices
  add column wifi_name text check (
    octet_length(wifi_name) between 1 and 32 and wifi_name !~ '[[:cntrl:]]'
  ),
  add column wifi_watch text check (
    wifi_watch in ('no_name', 'no_country', 'no_radio', 'uplink', 'learning', 'watching')
  );

-- devices_update_own already limits updates to the owner's rows.
grant update (wifi_name) on public.devices to authenticated;

create or replace function private.device_event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select array[
    'login_attempt', 'connection', 'port_scan',
    'host_scan', 'arp_spoof', 'rogue_dhcp', 'rogue_router', 'name_poisoning', 'ids_alert',
    'evil_twin', 'new_access_point'
  ]
$$;

-- As in 20261107140000 (its latest definition), plus the Wi-Fi watch fields: the access point's
-- security and the security learned for the home network from a fixed
-- vocabulary, its channel and its signal in dBm.
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
        when d.key in ('security', 'home_security') then
          pg_catalog.jsonb_typeof(d.value) <> 'string'
          or (d.value #>> '{}') not in ('open', 'wep', 'wpa', 'wpa2', 'wpa3')
        when d.key = 'channel' then
          pg_catalog.jsonb_typeof(d.value) <> 'number'
          or (d.value #>> '{}') !~ '^[1-9][0-9]{0,2}$'
        when d.key = 'signal_dbm' then
          pg_catalog.jsonb_typeof(d.value) <> 'number'
          or (d.value #>> '{}') !~ '^(0|-[1-9][0-9]{0,2})$'
        else true
      end
    )
$$;

-- As in 20261107193200, plus the Wi-Fi watch: an access point with the home
-- network's name and weaker security than the home network is critical (an
-- evil twin); a new one with the same security is a warning, because mesh
-- systems and new access points are common.
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
    when kind in (
      'login_attempt', 'arp_spoof', 'rogue_dhcp', 'name_poisoning', 'dns_hijack', 'evil_twin'
    ) then 'critical'
    when kind in (
      'connection', 'port_scan', 'host_scan', 'rogue_router', 'device_offline', 'port_forward',
      'new_access_point'
    ) then 'warning'
    when kind in ('device_online', 'new_host') then 'info'
  end
$$;

-- What the watch needs from the backend, and where the device reports how
-- the watch is doing. Returns {"name": the home network's name or null,
-- "country": the regulatory country or null}. The country is the one the
-- platform's edge (Cloudflare) puts in the cf-ipcountry request header for
-- the device's own request; without it the device keeps its radio off. XX
-- (unknown) and T1 (Tor) are not countries.
create function public.device_wifi_v1(device_id uuid, secret text, watch text)
  returns jsonb
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_country text;
  v_name text;
begin
  perform private.authenticate_device(device_wifi_v1.device_id, device_wifi_v1.secret);

  -- PostgREST sets the headers as a JSON object; outside a request the
  -- setting is unset or empty.
  v_country := nullif(pg_catalog.current_setting('request.headers', true), '')::jsonb ->> 'cf-ipcountry';

  update public.devices as d
  set wifi_watch = device_wifi_v1.watch
  where d.id = device_wifi_v1.device_id
  returning d.wifi_name into v_name;

  return pg_catalog.jsonb_build_object(
    'name', v_name,
    'country', case
      when v_country ~ '^[A-Z]{2}$' and v_country not in ('XX', 'T1') then v_country
    end
  );
end;
$$;

revoke all on function public.device_wifi_v1(uuid, text, text) from public, anon, authenticated, service_role;
grant execute on function public.device_wifi_v1(uuid, text, text) to anon;

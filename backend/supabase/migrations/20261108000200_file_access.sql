-- The decoy file share (ADR 0018). Opening a file on it is a new kind,
-- file_access, and critical: only someone looking for data does that. Its
-- detail, and that of a connection to a share, names the share and the file.
-- Sign-ins and share connections use the existing kinds. Additive for
-- devices in the field: nothing an existing agent sends changes meaning.

-- As in 20261107235000_wifi_watch.sql (its latest definition), plus
-- file_access.
create or replace function private.device_event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select array[
    'login_attempt', 'connection', 'port_scan',
    'host_scan', 'arp_spoof', 'rogue_dhcp', 'rogue_router', 'name_poisoning', 'ids_alert',
    'evil_twin', 'new_access_point', 'file_access'
  ]
$$;

-- As in 20261107235000_wifi_watch.sql, plus the share and the file, bounded
-- strings without control characters like every other attacker-influenced
-- name.
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
          'claimed_ip', 'previous_mac', 'server_ip', 'protocol', 'signature', 'category',
          'share', 'file'
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

-- As in 20261107235000_wifi_watch.sql, with file_access among the critical
-- kinds. private.event_kinds() takes the device kinds from the list above,
-- so it needs no change.
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
      'login_attempt', 'arp_spoof', 'rogue_dhcp', 'name_poisoning', 'dns_hijack', 'evil_twin',
      'file_access'
    ) then 'critical'
    when kind in (
      'connection', 'port_scan', 'host_scan', 'rogue_router', 'device_offline', 'port_forward',
      'new_access_point'
    ) then 'warning'
    when kind in ('device_online', 'new_host') then 'info'
  end
$$;

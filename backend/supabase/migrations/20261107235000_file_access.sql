-- The decoy file share (ADR 0018). Opening a file on it is a new kind,
-- file_access, and critical: only someone looking for data does that. Its
-- detail, and that of a connection to a share, names the share and the file.
-- Sign-ins and share connections use the existing kinds. Additive for
-- devices in the field: nothing an existing agent sends changes meaning.

create or replace function private.device_event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select array[
    'login_attempt', 'connection', 'port_scan',
    'host_scan', 'arp_spoof', 'rogue_dhcp', 'rogue_router', 'name_poisoning', 'ids_alert',
    'file_access'
  ]
$$;

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
        else true
      end
    )
$$;

-- As in 20261107193200_event_vocabulary.sql, with file_access among the
-- critical kinds. private.event_kinds() takes the device kinds from the
-- list above, so it needs no change.
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
      'login_attempt', 'arp_spoof', 'rogue_dhcp', 'name_poisoning', 'dns_hijack', 'file_access'
    ) then 'critical'
    when kind in (
      'connection', 'port_scan', 'host_scan', 'rogue_router', 'device_offline', 'port_forward'
    ) then 'warning'
    when kind in ('device_online', 'new_host') then 'info'
  end
$$;

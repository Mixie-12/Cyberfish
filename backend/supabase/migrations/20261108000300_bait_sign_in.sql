-- Honeytoken user names (ADR 0024). The decoy's bait files carry fake
-- credentials; a sign-in with one of their user names is flagged by the
-- agent with "bait": true in the detail. Only the boolean is new: the user
-- name was reported already, and the password never leaves the device.
-- Additive for devices in the field: no agent sent this key before.

-- As in 20261108000200_file_access.sql (its latest definition), plus bait
-- as a boolean.
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
        when d.key = 'bait' then
          pg_catalog.jsonb_typeof(d.value) <> 'boolean'
        else true
      end
    )
$$;

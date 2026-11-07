-- Network health (20261107183000) and new devices (20261107193000) were
-- written in parallel; the later one redefined the event kinds and their
-- severity without dns_hijack and port_forward. This puts every backend
-- kind back into the single list and every kind into the severity mapping.
-- A pgTAP test (12_event_vocabulary) fails when a kind has no severity.
create or replace function private.event_kinds()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select private.device_event_kinds()
    || array['device_offline', 'device_online', 'new_host', 'dns_hijack', 'port_forward']
$$;

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
    when kind in ('device_online', 'new_host') then 'info'
  end
$$;

-- Every event kind has a severity, so no kind can be added in one migration
-- and forgotten in another.
begin;
create extension if not exists pgtap with schema extensions;
select plan(3);

select is_empty(
  $$ select k from pg_catalog.unnest(private.event_kinds()) as k
     where private.event_severity(k, '{}') is null $$,
  'every event kind has a severity'
);

select ok(
  private.event_kinds() @> array['new_host', 'dns_hijack', 'port_forward', 'device_offline', 'device_online'],
  'the backend kinds of ADR 0015 are all accepted'
);

-- The whole vocabulary and its severities, so a migration that redefines
-- the functions from an older base cannot drop a kind unnoticed.
select results_eq(
  $$ select k, private.event_severity(k, '{}')
     from pg_catalog.unnest(private.event_kinds()) as k
     order by k collate "C" $$,
  $$ values
       ('arp_spoof', 'critical'),
       ('connection', 'warning'),
       ('device_offline', 'warning'),
       ('device_online', 'info'),
       ('dns_hijack', 'critical'),
       ('evil_twin', 'critical'),
       ('file_access', 'critical'),
       ('host_scan', 'warning'),
       ('ids_alert', 'warning'),
       ('login_attempt', 'critical'),
       ('name_poisoning', 'critical'),
       ('new_access_point', 'warning'),
       ('new_host', 'info'),
       ('port_forward', 'warning'),
       ('port_scan', 'warning'),
       ('rogue_dhcp', 'critical'),
       ('rogue_router', 'warning') $$,
  'every event kind has the severity its ADR decided'
);

select * from finish();
rollback;

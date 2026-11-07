-- Every event kind has a severity, so no kind can be added in one migration
-- and forgotten in another.
begin;
create extension if not exists pgtap with schema extensions;
select plan(2);

select is_empty(
  $$ select k from pg_catalog.unnest(private.event_kinds()) as k
     where private.event_severity(k, '{}') is null $$,
  'every event kind has a severity'
);

select ok(
  private.event_kinds() @> array['new_host', 'dns_hijack', 'port_forward', 'device_offline', 'device_online'],
  'the backend kinds of ADR 0015 are all accepted'
);

select * from finish();
rollback;

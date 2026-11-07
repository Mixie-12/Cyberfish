-- The privilege model as a whole: what each API role can reach, and the
-- invariants every future migration must keep.
begin;
create extension if not exists pgtap with schema extensions;
select plan(11);

select is_empty(
  $$
    select c.oid::regclass::text
    from pg_class as c
    join pg_namespace as n on n.oid = c.relnamespace
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p')
      and not c.relrowsecurity
  $$,
  'every table in public and private has row level security enabled'
);

select is_empty(
  $$
    select p.oid::regprocedure::text
    from pg_proc as p
    join pg_namespace as n on n.oid = p.pronamespace
    where n.nspname in ('public', 'private', 'guard')
      and not coalesce('search_path=""' = any (p.proconfig), false)
  $$,
  'every function in public, private and guard pins an empty search_path'
);

select is_empty(
  $$
    select r.role || ' ' || c.oid::regclass::text
    from pg_class as c
    join pg_namespace as n on n.oid = c.relnamespace
    cross join (values ('anon'), ('service_role')) as r (role)
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p', 'v', 'm', 'f')
      and (
        has_table_privilege(r.role, c.oid, 'select,insert,update,delete,truncate,references,trigger')
        or has_any_column_privilege(r.role, c.oid, 'select,insert,update,references')
      )
  $$,
  'anon and service_role hold no table privileges'
);

select results_eq(
  $$
    select c.relname::text collate "default", p.privilege
    from pg_class as c
    join pg_namespace as n on n.oid = c.relnamespace
    cross join unnest(array['select', 'insert', 'update', 'delete', 'truncate', 'references', 'trigger'])
      as p (privilege)
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p')
      and has_table_privilege('authenticated', c.oid, p.privilege)
    order by 1, 2
  $$,
  $$
    values
      ('devices', 'delete'),
      ('devices', 'select'),
      ('events', 'select'),
      ('health_samples', 'select'),
      ('network_hosts', 'select'),
      ('networks', 'select'),
      ('owner_settings', 'select'),
      ('port_forwards', 'select'),
      ('speed_tests', 'select')
  $$,
  'authenticated holds exactly the expected table privileges'
);

select results_eq(
  $$
    select (c.relname::text || '.' || a.attname::text) collate "default"
    from pg_attribute as a
    join pg_class as c on c.oid = a.attrelid
    join pg_namespace as n on n.oid = c.relnamespace
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p')
      and a.attnum > 0
      and not a.attisdropped
      and has_column_privilege('authenticated', c.oid, a.attnum, 'insert')
    order by 1
  $$,
  $$ values ('owner_settings.weekly_summary') $$,
  'authenticated may insert only the weekly summary setting'
);

select results_eq(
  $$
    select (c.relname::text || '.' || a.attname::text) collate "default"
    from pg_attribute as a
    join pg_class as c on c.oid = a.attrelid
    join pg_namespace as n on n.oid = c.relnamespace
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p')
      and a.attnum > 0
      and not a.attisdropped
      and has_column_privilege('authenticated', c.oid, a.attnum, 'update')
    order by 1
  $$,
  $$ values ('devices.name'), ('devices.speed_test'), ('devices.wifi_name'), ('network_hosts.label'), ('network_hosts.room'), ('network_hosts.trusted'), ('owner_settings.weekly_summary') $$,
  'authenticated may update only device names, the speed test setting, Wi-Fi names, host names, rooms and trust, and the weekly summary setting'
);

select results_eq(
  $$
    select r.role, p.proname::text collate "default"
    from pg_proc as p
    join pg_namespace as n on n.oid = p.pronamespace
    cross join (values ('anon'), ('authenticated'), ('service_role')) as r (role)
    where n.nspname in ('public', 'private', 'guard')
      and has_function_privilege(r.role, p.oid, 'execute')
    order by 1, 2
  $$,
  $$
    values
      ('anon', 'check_request'),
      ('anon', 'device_events_v1'),
      ('anon', 'device_health_v1'),
      ('anon', 'device_heartbeat_v1'),
      ('anon', 'device_heartbeat_v2'),
      ('anon', 'device_hosts_v1'),
      ('anon', 'device_hosts_v2'),
      ('anon', 'device_register_v1'),
      ('anon', 'device_wifi_v1'),
      ('authenticated', 'acknowledge_events_v1'),
      ('authenticated', 'check_request'),
      ('authenticated', 'claim_device_v1'),
      ('authenticated', 'delete_account_v1'),
      ('authenticated', 'register_push_token_v1'),
      ('authenticated', 'register_push_token_v2'),
      ('authenticated', 'unregister_push_token_v1'),
      ('service_role', 'check_request'),
      ('service_role', 'forget_push_token_v1'),
      ('service_role', 'notification_targets_v1'),
      ('service_role', 'notification_targets_v2'),
      ('service_role', 'notification_targets_v3'),
      ('service_role', 'weekly_summary_v1'),
      ('service_role', 'weekly_summary_v2')
  $$,
  'API roles can execute exactly the published functions'
);

select ok(
  not has_schema_privilege('anon', 'private', 'usage')
    and not has_schema_privilege('authenticated', 'private', 'usage')
    and not exists (select 1 from pg_extension where extname = 'pg_graphql'),
  'the private schema is unreachable and GraphQL is disabled'
);

select is_empty(
  $$ select pubname || ' ' || schemaname || '.' || tablename from pg_publication_tables $$,
  'no table is published to Realtime'
);

-- The default privileges themselves: what a future migration creates starts
-- closed until it grants something.
create table public.hardening_probe (id integer);
create function public.hardening_probe() returns integer language sql as 'select 1';
create schema hardening_probe;
create function hardening_probe.probe() returns integer language sql as 'select 1';

select is_empty(
  $$
    select r.role
    from (values ('anon'), ('authenticated'), ('service_role'), ('public')) as r (role)
    where has_table_privilege(r.role, 'public.hardening_probe', 'select,insert,update,delete,truncate,references,trigger')
      or has_function_privilege(r.role, 'public.hardening_probe()', 'execute')
      or has_function_privilege(r.role, 'hardening_probe.probe()', 'execute')
  $$,
  'new tables and functions start without privileges for the API roles'
);

select is_empty(
  $$
    select r.role || ' ' || s.schema
    from (values ('anon'), ('authenticated'), ('service_role')) as r (role)
    cross join (values ('public'), ('private'), ('guard')) as s (schema)
    where has_schema_privilege(r.role, s.schema, 'create')
  $$,
  'no API role can create objects in public, private or guard'
);

select * from finish();
rollback;

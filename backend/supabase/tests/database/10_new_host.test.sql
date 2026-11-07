-- new_host events (ADR 0015): the learning period, first sightings only,
-- isolation between owners, and how they notify.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B device');

insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000a1', private.sha256_text('C' || repeat('0', 24) || 'A'),
   private.sha256_text(repeat('a', 64))),
  ('00000000-0000-0000-0000-0000000000b1', private.sha256_text('C' || repeat('0', 24) || 'B'),
   private.sha256_text(repeat('b', 64)));

insert into private.push_tokens (token, user_id) values
  ('a-phone', '00000000-0000-0000-0000-00000000000a');

-- Reports hosts the way an agent does: as anon, with the device secret.
create function pg_temp.report(device_id uuid, secret text, hosts jsonb)
  returns integer
  language sql
as $$
  select public.device_hosts_v2(device_id, secret, hosts, '[]')
$$;

grant execute on function pg_temp.report(uuid, text, jsonb) to anon;

-- Moves a device's inventory back in time, as if its first rows were older.
create function pg_temp.age_inventory(device_id uuid, age interval)
  returns void
  language sql
as $$
  update public.network_hosts as h
  set first_seen_at = now() - age
  where h.device_id = age_inventory.device_id
$$;

create function pg_temp.new_hosts(device_id uuid)
  returns setof text
  language sql
as $$
  select e.src_mac::text
  from public.events as e
  where e.device_id = new_hosts.device_id and e.kind = 'new_host'
  order by e.id
$$;

set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
  {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.1", "kind": "router"},
  {"mac": "3c:22:fb:00:00:02", "ip": "192.168.1.2"}
]$json$);
reset role;

select is_empty(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000a1') $$,
  'the first inventory creates no new_host events'
);

select pg_temp.age_inventory('00000000-0000-0000-0000-0000000000a1', interval '23 hours 59 minutes');
set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
  '[{"mac": "3c:22:fb:00:00:03", "ip": "192.168.1.3"}]');
reset role;

select is_empty(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000a1') $$,
  'a host first seen within 24 hours of the first inventory is not new'
);

select pg_temp.age_inventory('00000000-0000-0000-0000-0000000000a1', interval '24 hours');
set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
  {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.1", "kind": "router"},
  {"mac": "3c:22:fb:00:00:04", "ip": "192.168.1.4", "kind": "tv"}
]$json$);
reset role;

select results_eq(
  $$ select src_mac::text, host(src_ip), severity, occurred_at = now(), detail
     from public.events where kind = 'new_host' $$,
  $$ values ('3c:22:fb:00:00:04', '192.168.1.4', 'info', true, '{}'::jsonb) $$,
  'after the learning period, the first sighting is an info event with the address and nothing else'
);

set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
  {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.1"},
  {"mac": "3c:22:fb:00:00:02", "ip": "192.168.1.22"},
  {"mac": "3c:22:fb:00:00:03", "ip": "192.168.1.3"},
  {"mac": "3c:22:fb:00:00:04", "ip": "192.168.1.4"}
]$json$);
reset role;

select results_eq(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000a1') $$,
  $$ values ('3c:22:fb:00:00:04') $$,
  'known addresses are not new, even with a new IP address'
);

set local role anon;
select public.device_hosts_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
  {"mac": "3c:22:fb:00:00:05", "ip": "192.168.1.5"},
  {"mac": "3c:22:fb:00:00:06", "ip": "192.168.1.6"}
]$json$);
reset role;

select results_eq(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000a1') $$,
  $$ values ('3c:22:fb:00:00:04'), ('3c:22:fb:00:00:05'), ('3c:22:fb:00:00:06') $$,
  'agents on device_hosts_v1 produce new_host events too, one per new address'
);

set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000b1', repeat('b', 64), $json$[
  {"mac": "3c:22:fb:00:00:01", "ip": "10.0.0.1"},
  {"mac": "3c:22:fb:00:00:0b", "ip": "10.0.0.11"}
]$json$);
reset role;

select is_empty(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000b1') $$,
  'each DeKoi has its own learning period, whatever other DeKoi devices know'
);

select pg_temp.age_inventory('00000000-0000-0000-0000-0000000000b1', interval '2 days');
set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000b1', repeat('b', 64),
  '[{"mac": "3c:22:fb:00:00:04", "ip": "10.0.0.4"}]');
reset role;

select results_eq(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000b1') $$,
  $$ values ('3c:22:fb:00:00:04') $$,
  'an address another owner''s DeKoi knows is still new to this one'
);

select results_eq(
  $$ select * from pg_temp.new_hosts('00000000-0000-0000-0000-0000000000a1') $$,
  $$ values ('3c:22:fb:00:00:04'), ('3c:22:fb:00:00:05'), ('3c:22:fb:00:00:06') $$,
  'another owner''s inventory creates no events on this owner''s DeKoi'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select results_eq(
  $$ select device_id, src_mac::text from public.events where kind = 'new_host' $$,
  $$ values ('00000000-0000-0000-0000-0000000000b1'::uuid, '3c:22:fb:00:00:04') $$,
  'an owner reads only their own new_host events'
);

reset role;

set local role anon;
select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       jsonb_build_array(jsonb_build_object('agent_event_id', 1, 'occurred_at', now(), 'kind', 'new_host'))) $$,
  '22023', 'invalid argument',
  'a device cannot report new_host itself'
);
reset role;

select is_empty(
  $$ select k from unnest(private.event_kinds()) as k where private.event_severity(k, '{}') is null $$,
  'no event kind is without a severity'
);

select ok(
  'new_host' = any (private.event_kinds()) and not 'new_host' = any (private.device_event_kinds()),
  'new_host is an event kind only the backend writes'
);

create function pg_temp.targets(mac macaddr)
  returns setof record
  language sql
as $$
  select t.token, t.template, t.severity, t.host_kind
  from public.notification_targets_v2((
    select e.id from public.events as e
    where e.device_id = '00000000-0000-0000-0000-0000000000a1' and e.src_mac = targets.mac
    order by e.id desc
    limit 1
  )) as t
$$;

select results_eq(
  $$ select * from pg_temp.targets('3c:22:fb:00:00:04') as t (token text, template text, severity text, host_kind text) $$,
  $$ values ('a-phone', 'new_host', 'info', 'tv') $$,
  'a new device notifies the owner with its kind, as information'
);

select is_empty(
  $$ select * from pg_temp.targets('3c:22:fb:00:00:05') as t (token text, template text, severity text, host_kind text) $$,
  'another new device within 10 minutes does not notify again'
);

insert into public.events (device_id, agent_event_id, occurred_at, kind, src_ip, src_mac) values
  ('00000000-0000-0000-0000-0000000000a1', 1, now(), 'port_scan', '192.168.1.5', '3c:22:fb:00:00:05');

select results_eq(
  $$ select template, severity from pg_temp.targets('3c:22:fb:00:00:05') as t (token text, template text, severity text, host_kind text) $$,
  $$ values ('port_scan', 'warning') $$,
  'what a new device does is still reported on its own'
);

update private.notified_sources set notified_at = now() - interval '10 minutes' where source = 'new_host';

select results_eq(
  $$ select template from pg_temp.targets('3c:22:fb:00:00:06') as t (token text, template text, severity text, host_kind text) $$,
  $$ values ('new_host') $$,
  'new devices notify again after 10 minutes'
);

delete from public.network_hosts where device_id = '00000000-0000-0000-0000-0000000000a1' and mac = '3c:22:fb:00:00:06';
set local role anon;
select pg_temp.report('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
  '[{"mac": "3c:22:fb:00:00:06", "ip": "192.168.1.6"}]');
reset role;

select is(
  (select count(*)::integer from public.events where kind = 'new_host' and src_mac = '3c:22:fb:00:00:06'),
  2,
  'a host removed by retention is new again when it returns'
);

select * from finish();
rollback;

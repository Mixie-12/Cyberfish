-- Users only ever see and change their own rows. Abuse cases 1 and 2.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B device');

insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000a1', now(), 'device_online'),
  ('00000000-0000-0000-0000-0000000000b1', now(), 'device_online');

insert into public.network_hosts (device_id, mac, ip) values
  ('00000000-0000-0000-0000-0000000000a1', '3c:22:fb:00:00:0a', '192.168.1.10'),
  ('00000000-0000-0000-0000-0000000000b1', '3c:22:fb:00:00:0b', '192.168.2.10');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select results_eq('select name from public.devices', $$ values ('A device') $$, 'A sees only A''s devices');
select is((select count(*)::integer from public.events), 1, 'A sees only A''s events');
select is((select count(*)::integer from public.network_hosts), 1, 'A sees only A''s network hosts');

select lives_ok(
  $$ update public.devices set name = 'Renamed by A' $$,
  'A may rename devices'
);

select lives_ok(
  $$ delete from public.devices where id = '00000000-0000-0000-0000-0000000000b1' $$,
  'deleting another account''s device runs but matches nothing'
);

select throws_ok(
  $$ update public.devices set owner_id = '00000000-0000-0000-0000-00000000000a' $$,
  '42501', null,
  'A cannot change device ownership'
);

select throws_ok(
  $$ insert into public.events (device_id, occurred_at, kind)
     values ('00000000-0000-0000-0000-0000000000a1', now(), 'port_scan') $$,
  '42501', null,
  'A cannot insert events'
);

select throws_ok(
  $$ insert into public.devices (owner_id, name) values ('00000000-0000-0000-0000-00000000000a', 'x') $$,
  '42501', null,
  'A cannot create devices except by claiming'
);

select lives_ok(
  $$ update public.network_hosts set trusted = true $$,
  'A may mark hosts as trusted'
);

select throws_ok(
  $$ update public.network_hosts set vendor = 'spoofed' $$,
  '42501', null,
  'A cannot change host data reported by the device'
);

select throws_ok(
  $$ select count(*) from private.device_credentials $$,
  '42501', null,
  'A cannot read device credentials'
);

select throws_ok(
  $$ select count(*) from private.push_tokens $$,
  '42501', null,
  'A cannot read push tokens'
);

reset role;
set local role anon;

select throws_ok($$ select count(*) from public.devices $$, '42501', null, 'anon cannot read devices');
select throws_ok($$ select count(*) from public.events $$, '42501', null, 'anon cannot read events');
select throws_ok($$ select count(*) from public.network_hosts $$, '42501', null, 'anon cannot read network hosts');

reset role;

select results_eq(
  'select name from public.devices order by name',
  $$ values ('B device'), ('Renamed by A') $$,
  'only A''s device was renamed and B''s device still exists'
);

select results_eq(
  'select mac::text, trusted from public.network_hosts order by mac',
  $$ values ('3c:22:fb:00:00:0a', true), ('3c:22:fb:00:00:0b', false) $$,
  'only A''s host was marked trusted'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ delete from public.devices where id = '00000000-0000-0000-0000-0000000000a1' $$,
  'A may remove A''s own device'
);

reset role;

select ok(
  not exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from public.network_hosts where device_id = '00000000-0000-0000-0000-0000000000a1')
    and exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000b1'),
  'removing a device removes its data and nothing else'
);

select * from finish();
rollback;

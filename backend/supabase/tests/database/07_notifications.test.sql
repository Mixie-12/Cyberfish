-- Which events notify whom, and the database webhook.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B device');

insert into private.push_tokens (token, user_id) values
  ('a-phone', '00000000-0000-0000-0000-00000000000a'),
  ('a-tablet', '00000000-0000-0000-0000-00000000000a'),
  ('b-phone', '00000000-0000-0000-0000-00000000000b');

insert into public.network_hosts (device_id, mac, ip, trusted) values
  ('00000000-0000-0000-0000-0000000000a1', 'aa:bb:cc:00:00:01', '192.168.1.20', true),
  ('00000000-0000-0000-0000-0000000000a1', 'aa:bb:cc:00:00:02', '192.168.1.21', false);

insert into public.events (device_id, agent_event_id, occurred_at, kind, src_ip, src_mac) values
  ('00000000-0000-0000-0000-0000000000a1', 1, now(), 'device_online', null, null),
  ('00000000-0000-0000-0000-0000000000a1', 2, now(), 'device_offline', null, null),
  ('00000000-0000-0000-0000-0000000000a1', 3, now(), 'login_attempt', '192.168.1.50', null),
  ('00000000-0000-0000-0000-0000000000a1', 4, now(), 'port_scan', '192.168.1.50', null),
  ('00000000-0000-0000-0000-0000000000a1', 5, now(), 'connection', '192.168.1.51', null),
  ('00000000-0000-0000-0000-0000000000a1', 6, now(), 'login_attempt', '192.168.1.50', null),
  ('00000000-0000-0000-0000-0000000000a1', 7, now(), 'arp_spoof', null, 'aa:bb:cc:00:00:01'),
  ('00000000-0000-0000-0000-0000000000a1', 8, now(), 'login_attempt', '192.168.1.20', 'aa:bb:cc:00:00:01'),
  ('00000000-0000-0000-0000-0000000000a1', 9, now(), 'host_scan', null, 'aa:bb:cc:00:00:02'),
  ('00000000-0000-0000-0000-0000000000a1', 10, now(), 'rogue_dhcp', null, 'aa:bb:cc:00:00:02');

create function pg_temp.targets(agent_event_id bigint)
  returns setof record
  language sql
as $$
  select t.token, t.template
  from public.notification_targets_v1(
    (select e.id from public.events as e where e.agent_event_id = targets.agent_event_id)
  ) as t
  order by 1
$$;

select is_empty(
  'select * from pg_temp.targets(1) as t (token text, template text)',
  'device_online never notifies'
);

select results_eq(
  'select * from pg_temp.targets(2) as t (token text, template text)',
  $$ values ('a-phone', 'device_offline'), ('a-tablet', 'device_offline') $$,
  'device_offline notifies every phone of the owner, and only the owner'
);

select results_eq(
  'select count(*)::integer from pg_temp.targets(2) as t (token text, template text)',
  'values (2)',
  'device_offline is never coalesced'
);

select results_eq(
  'select * from pg_temp.targets(3) as t (token text, template text)',
  $$ values ('a-phone', 'login_attempt'), ('a-tablet', 'login_attempt') $$,
  'the first event from a source notifies with the template of its kind'
);

select is_empty(
  'select * from pg_temp.targets(4) as t (token text, template text)',
  'a second event from the same source within 10 minutes does not notify'
);

select results_eq(
  'select count(*)::integer from pg_temp.targets(5) as t (token text, template text)',
  'values (2)',
  'an event from another source notifies'
);

update private.notified_sources set notified_at = now() - interval '10 minutes' where source = '192.168.1.50';

select results_eq(
  'select count(*)::integer from pg_temp.targets(6) as t (token text, template text)',
  'values (2)',
  'the same source notifies again after 10 minutes'
);

select is_empty(
  'select * from pg_temp.targets(7) as t (token text, template text)',
  'a trusted source never notifies'
);

select is_empty(
  'select * from pg_temp.targets(8) as t (token text, template text)',
  'a trusted source never notifies, whatever its IP address'
);

select results_eq(
  'select count(*)::integer from pg_temp.targets(9) as t (token text, template text)',
  'values (2)',
  'without an IP address the hardware address is the source'
);

select is_empty(
  'select * from pg_temp.targets(10) as t (token text, template text)',
  'events from one hardware address are coalesced'
);

select is_empty(
  'select * from public.notification_targets_v1(-1)',
  'an unknown event notifies nobody'
);

insert into public.events (device_id, agent_event_id, occurred_at, kind, src_ip)
select '00000000-0000-0000-0000-0000000000b1', k.n, now(), k.kind, ('10.0.0.' || k.n)::inet
from unnest(private.device_event_kinds()) with ordinality as k (kind, n);

select is(
  (
    select count(*)::integer
    from public.events as e
    cross join lateral public.notification_targets_v1(e.id) as t
    where e.device_id = '00000000-0000-0000-0000-0000000000b1' and t.token = 'b-phone' and t.template = e.kind
  ),
  cardinality(private.device_event_kinds()),
  'every kind a device may report can notify'
);

set local role service_role;
select lives_ok('select public.notification_targets_v1(-1)', 'service_role may ask who to notify');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select throws_ok(
  'select public.notification_targets_v1(-1)',
  '42501',
  null,
  'a signed-in user may not ask who to notify'
);

set local role anon;
select throws_ok(
  'select public.notification_targets_v1(-1)',
  '42501',
  null,
  'an anonymous caller may not ask who to notify'
);

reset role;

set local role service_role;
select lives_ok($$ select public.forget_push_token_v1('a-tablet') $$, 'service_role forgets an unregistered token');
reset role;

select results_eq(
  'select token from private.push_tokens order by 1',
  $$ values ('a-phone'), ('b-phone') $$,
  'only the forgotten token is gone'
);

delete from vault.secrets where name in ('notify_url', 'notify_secret');
select vault.create_secret('http://notify.test.invalid/functions/v1/notify', 'notify_url');
select vault.create_secret('test-shared-secret', 'notify_secret');

insert into public.events (device_id, agent_event_id, occurred_at, kind, src_ip, detail) values
  ('00000000-0000-0000-0000-0000000000a1', 100, now(), 'login_attempt', '192.168.1.60', '{"username": "attacker text"}');

select results_eq(
  $$
    select q.url, q.headers ->> 'X-Notify-Secret', convert_from(q.body, 'UTF8')::jsonb
    from net.http_request_queue as q
    where q.url = 'http://notify.test.invalid/functions/v1/notify'
  $$,
  $$
    select
      'http://notify.test.invalid/functions/v1/notify'::text,
      'test-shared-secret'::text,
      jsonb_build_object('event_id', e.id)
    from public.events as e
    where e.agent_event_id = 100
  $$,
  'an inserted event is posted to notify as its id and the shared secret, nothing else'
);

select * from finish();
rollback;

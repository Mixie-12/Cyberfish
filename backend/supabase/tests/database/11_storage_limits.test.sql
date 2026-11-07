-- Storage limits and the notify gate (ADR 0016).
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid'),
  ('00000000-0000-0000-0000-00000000000c', 'c@test.invalid');

insert into public.devices (id, owner_id, name, registered_at) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A one', now()),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000000a', 'A two', now()),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B one', now());

insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000a1', private.sha256_text('A1'), private.sha256_text(repeat('a', 64)));

-- Notify gate. Counts start empty in each section.
delete from vault.secrets where name in ('notify_url', 'notify_secret');
select vault.create_secret('http://notify.test.invalid/functions/v1/notify', 'notify_url');
select vault.create_secret('test-shared-secret', 'notify_secret');

create function pg_temp.posted() returns integer language sql as $$
  select count(*)::integer from net.http_request_queue where url = 'http://notify.test.invalid/functions/v1/notify'
$$;

select private.count_hits('notify 00000000-0000-0000-0000-00000000000a', interval '1 hour', 119);
insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000a1', now(), 'device_offline'),
  ('00000000-0000-0000-0000-0000000000a2', now(), 'device_offline'),
  ('00000000-0000-0000-0000-0000000000b1', now(), 'device_offline');

select is(pg_temp.posted(), 2, 'an account''s 121st event in the hour is stored without calling notify; others go on');
select is((select count(*)::integer from public.events), 3, 'events over the notify limit are still stored');

delete from private.rate_counters;
select private.count_hits('notify', interval '1 hour', 600);
insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000b1', now(), 'device_offline');
select is(pg_temp.posted(), 2, 'after 600 calls in the hour nobody''s events call notify');

select ok(
  (
    select pg_get_triggerdef(t.oid) like '%WHEN (private.may_notify(new.device_id))%'
    from pg_trigger as t
    where t.tgrelid = 'public.events'::regclass and t.tgname = 'events_post_to_notify'
  ),
  'the events webhook passes the notify gate'
);

-- Events a device reports.
delete from private.rate_counters;
delete from public.events;

select lives_ok(
  $$
    insert into public.events (device_id, agent_event_id, occurred_at, kind)
    select case when n % 2 = 0 then '00000000-0000-0000-0000-0000000000a1'::uuid else '00000000-0000-0000-0000-0000000000a2'::uuid end,
      n, now(), 'port_scan'
    from generate_series(1, 3600) as n
  $$,
  'an account''s devices may report 3600 events an hour'
);

select throws_ok(
  $$ insert into public.events (device_id, agent_event_id, occurred_at, kind) values ('00000000-0000-0000-0000-0000000000a2', 10000, now(), 'port_scan') $$,
  'PT429', 'too many events',
  'the next reported event in the hour is refused'
);

set local role anon;
select throws_ok(
  $$
    select public.device_events_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
      jsonb_build_array(jsonb_build_object('agent_event_id', 10001, 'occurred_at', now(), 'kind', 'port_scan')))
  $$,
  'PT429', 'too many events',
  'the device API answers with HTTP 429 and the agent keeps the batch'
);
reset role;

select lives_ok(
  $$ insert into public.events (device_id, occurred_at, kind) values ('00000000-0000-0000-0000-0000000000a1', now(), 'device_offline') $$,
  'the backend''s own events are never refused'
);

select lives_ok(
  $$ insert into public.events (device_id, agent_event_id, occurred_at, kind) values ('00000000-0000-0000-0000-0000000000b1', 1, now(), 'port_scan') $$,
  'another account reports on'
);

select lives_ok(
  $$
    insert into public.events (device_id, agent_event_id, occurred_at, kind)
    values ('00000000-0000-0000-0000-0000000000a1', 2, now(), 'port_scan')
    on conflict on constraint events_device_id_agent_event_id_key do nothing
  $$,
  'a resent event that inserts nothing does not count'
);

-- Hosts and networks per device.
select lives_ok(
  $$
    insert into public.network_hosts (device_id, mac, ip)
    select '00000000-0000-0000-0000-0000000000a1', lpad(to_hex(n), 12, '0')::macaddr, '10.0.0.1'
    from generate_series(1, 4096) as n
  $$,
  'a device may store 4096 hosts'
);

select throws_ok(
  $$ insert into public.network_hosts (device_id, mac, ip) values ('00000000-0000-0000-0000-0000000000a1', 'ff:00:00:00:00:01', '10.0.0.2') $$,
  'PT429', 'too many hosts',
  'a 4097th host is refused'
);

select lives_ok(
  $$
    insert into public.network_hosts (device_id, mac, ip) values ('00000000-0000-0000-0000-0000000000a1', '00:00:00:00:00:01', '10.0.0.3')
    on conflict on constraint network_hosts_pkey do update set ip = excluded.ip, last_seen_at = now()
  $$,
  'known hosts are still updated'
);

select lives_ok(
  $$
    insert into public.networks (device_id, cidr, source)
    select '00000000-0000-0000-0000-0000000000a1', ('10.' || n / 256 || '.' || n % 256 || '.0/24')::cidr, 'observed'
    from generate_series(1, 256) as n
  $$,
  'a device may store 256 networks'
);

select throws_ok(
  $$ insert into public.networks (device_id, cidr, source) values ('00000000-0000-0000-0000-0000000000a1', '192.168.0.0/24', 'observed') $$,
  'PT429', 'too many networks',
  'a 257th network is refused'
);

-- Devices per account.
insert into public.devices (owner_id, name, registered_at)
select '00000000-0000-0000-0000-00000000000a', 'A ' || n, now() from generate_series(3, 10) as n;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select throws_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || 'A', 'Eleventh') $$,
  'PT429', 'too many devices',
  'an account may hold 10 devices'
);

-- Push tokens per account.
reset role;
insert into private.push_tokens (token, user_id, updated_at)
select 'old-' || n, '00000000-0000-0000-0000-00000000000c', now() - n * interval '1 minute'
from generate_series(1, 10) as n;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c"}', true);
select lives_ok($$ select public.register_push_token_v1('newest') $$, 'an eleventh phone registers');
reset role;

select results_eq(
  $$ select token from private.push_tokens where user_id = '00000000-0000-0000-0000-00000000000c' order by updated_at desc limit 1 $$,
  $$ values ('newest') $$,
  'the newest token is kept'
);
select ok(
  (select count(*) from private.push_tokens where user_id = '00000000-0000-0000-0000-00000000000c') = 10
    and not exists (select 1 from private.push_tokens where token = 'old-10'),
  'an account keeps its 10 newest tokens and the oldest is dropped'
);

-- Abandoned setups.
insert into public.devices (owner_id, name, created_at, registered_at) values
  ('00000000-0000-0000-0000-00000000000b', 'Abandoned', now() - interval '31 days', null),
  ('00000000-0000-0000-0000-00000000000b', 'Waiting', now() - interval '29 days', null),
  ('00000000-0000-0000-0000-00000000000b', 'Running', now() - interval '31 days', now() - interval '31 days');

select private.expire_pending_setups();

select results_eq(
  $$ select name from public.devices where owner_id = '00000000-0000-0000-0000-00000000000b' order by name $$,
  $$ values ('B one'), ('Running'), ('Waiting') $$,
  'setups whose DeKoi never registered within 30 days are removed'
);

select * from finish();
rollback;

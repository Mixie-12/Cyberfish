-- Device API: authentication, input validation, idempotency. Abuse cases 4
-- and 5.
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000000a', 'One'),
  ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-00000000000a', 'Two');

insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000d1', private.sha256_text('C' || repeat('0', 24) || '1'),
   private.sha256_text(repeat('a', 64))),
  ('00000000-0000-0000-0000-0000000000d2', private.sha256_text('C' || repeat('0', 24) || '2'),
   private.sha256_text(repeat('b', 64)));

set local role anon;

select throws_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000d1', repeat('c', 64), '0.1.0', '{ssh}', null) $$,
  '28000', 'device authentication failed',
  'a wrong secret is refused'
);

select throws_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000d2', repeat('a', 64), '0.1.0', '{ssh}', null) $$,
  '28000', 'device authentication failed',
  'a device cannot act as another device'
);

select lives_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), '0.1.0', '{ssh,http}', '192.168.1.20') $$,
  'a heartbeat with the right secret is accepted'
);

select lives_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), '0.1.0', '{ssh,http}', '192.168.1.20') $$,
  'repeated heartbeats are accepted'
);

select throws_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), '0.1.0', '{"SSH; drop"}', null) $$,
  '23514', null,
  'a malformed service name is refused'
);

select is(
  public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), $json$[
    {"agent_event_id": 1, "occurred_at": "now", "kind": "login_attempt", "service": "ssh",
     "src_ip": "192.168.1.50", "dst_port": 22, "detail": {"username": "admin"}},
    {"agent_event_id": 2, "occurred_at": "now", "kind": "port_scan",
     "src_ip": "192.168.1.50", "count": 3, "detail": {"ports": [21, 23, 445]}}
  ]$json$::jsonb),
  2,
  'a valid batch is stored'
);

select is(
  public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), $json$[
    {"agent_event_id": 1, "occurred_at": "now", "kind": "login_attempt", "service": "ssh"},
    {"agent_event_id": 2, "occurred_at": "now", "kind": "port_scan"}
  ]$json$::jsonb),
  0,
  'a resent batch stores nothing twice'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 3, "occurred_at": "now", "kind": "device_offline"}]') $$,
  '22023', 'invalid argument',
  'a device cannot report backend-only event kinds'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       (select jsonb_agg(jsonb_build_object('agent_event_id', n, 'occurred_at', now(), 'kind', 'connection'))
        from generate_series(10, 210) as n)) $$,
  '22023', 'invalid argument',
  'a batch over 200 events is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       jsonb_build_array(jsonb_build_object('agent_event_id', 4, 'occurred_at', now() + interval '1 hour', 'kind', 'connection'))) $$,
  '22023', 'invalid argument',
  'events from the future are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), '{"not": "an array"}') $$,
  '22023', 'invalid argument',
  'a batch that is not an array is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       jsonb_build_array(jsonb_build_object('agent_event_id', 5, 'occurred_at', now(), 'kind', 'connection',
         'detail', jsonb_build_object('path', '/x' || chr(27) || '[31m')))) $$,
  '23514', null,
  'control characters in details are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       jsonb_build_array(jsonb_build_object('agent_event_id', 6, 'occurred_at', now(), 'kind', 'connection',
         'detail', jsonb_build_object('password', 'hunter2')))) $$,
  '23514', null,
  'unknown detail keys, such as a password, are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       jsonb_build_array(jsonb_build_object('agent_event_id', 7, 'occurred_at', now(), 'kind', 'connection',
         'detail', jsonb_build_object('path', repeat('x', 300))))) $$,
  '23514', null,
  'oversized detail strings are refused'
);

select is(
  public.device_hosts_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), $json$[
    {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.50", "vendor": "Apple, Inc."},
    {"mac": "02:00:00:00:00:01", "ip": "192.168.1.51"}
  ]$json$::jsonb),
  2,
  'network hosts are stored'
);

select is(
  public.device_hosts_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
    '[{"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.60", "vendor": "Apple, Inc."}]'),
  1,
  'a known host is updated in place'
);

select throws_ok(
  $$ select count(*) from public.events $$,
  '42501', null,
  'devices cannot read events directly'
);

reset role;

select is(
  (select count(*)::integer from public.events
   where device_id = '00000000-0000-0000-0000-0000000000d1' and kind = 'device_online'),
  1,
  'coming online is recorded once'
);

select results_eq(
  $$ select mac::text, host(ip), is_private_mac from public.network_hosts order by mac $$,
  $$ values ('02:00:00:00:00:01', '192.168.1.51', true), ('3c:22:fb:00:00:01', '192.168.1.60', false) $$,
  'randomised MAC addresses are flagged as private'
);

select ok(
  (select online and services = '{ssh,http}' and local_ip = '192.168.1.20'
   from public.devices where id = '00000000-0000-0000-0000-0000000000d1'),
  'the heartbeat updates the device status'
);

select * from finish();
rollback;

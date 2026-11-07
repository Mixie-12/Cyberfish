-- Read state, names and rooms, monitoring status (ADR 0013). Abuse cases 1,
-- 2, 4 and 5 for the new function grants, column grants and device input.
begin;
create extension if not exists pgtap with schema extensions;
select plan(61);

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

-- Fixed ids, so calls under other roles can name events that RLS hides.
insert into public.events (id, device_id, agent_event_id, occurred_at, kind)
overriding system value values
  (9000001, '00000000-0000-0000-0000-0000000000a1', 1, now(), 'port_scan'),
  (9000002, '00000000-0000-0000-0000-0000000000a1', 2, now(), 'port_scan'),
  (9000003, '00000000-0000-0000-0000-0000000000a1', 3, now(), 'login_attempt'),
  (9000004, '00000000-0000-0000-0000-0000000000b1', 1, now(), 'port_scan');

insert into public.network_hosts (device_id, mac, ip) values
  ('00000000-0000-0000-0000-0000000000a1', '3c:22:fb:00:00:0a', '192.168.1.10'),
  ('00000000-0000-0000-0000-0000000000b1', '3c:22:fb:00:00:0b', '192.168.2.10');

-- A heartbeat from device a1 with the given report.
create function pg_temp.beat(monitors jsonb, images jsonb default '[]')
  returns void
  language sql
as $$
  select public.device_heartbeat_v2(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '0.2.0', '{ssh,http}',
    '192.168.1.20', monitors, images
  )
$$;

-- One valid monitor merged with the given fields, for the malformed variants.
create function pg_temp.monitor(fields jsonb)
  returns void
  language sql
as $$
  select pg_temp.beat(jsonb_build_array('{"name": "ids", "state": "ok"}'::jsonb || fields))
$$;

create function pg_temp.image(fields jsonb)
  returns void
  language sql
as $$
  select pg_temp.beat('[]', jsonb_build_array(
    '{"name": "ids", "version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"}'::jsonb || fields
  ))
$$;

grant execute on function pg_temp.beat(jsonb, jsonb) to anon;
grant execute on function pg_temp.monitor(jsonb) to anon;
grant execute on function pg_temp.image(jsonb) to anon;

-- Read state

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select is(
  public.acknowledge_events_v1('{9000001,9000002}'),
  2,
  'an owner marks their own events as seen'
);

select is(
  public.acknowledge_events_v1('{9000001,9000002,9000003}'),
  1,
  'events already seen are not counted again'
);

select is(
  public.acknowledge_events_v1('{9000004}'),
  0,
  'another owner''s event is not marked'
);

select is(
  public.acknowledge_events_v1('{}'),
  0,
  'an empty list marks nothing'
);

select is(
  public.acknowledge_events_v1((select array_agg(n) from generate_series(-500, -1) as n)),
  0,
  '500 ids are accepted'
);

select throws_ok(
  $$ select public.acknowledge_events_v1((select array_agg(n) from generate_series(1, 501) as n)) $$,
  '22023', 'invalid argument',
  'more than 500 ids are refused'
);

select throws_ok(
  $$ select public.acknowledge_events_v1(null) $$,
  '22023', 'invalid argument',
  'a missing list is refused'
);

select results_eq(
  $$ select seen_at is not null from public.events order by agent_event_id $$,
  $$ values (true), (true), (true) $$,
  'an owner reads the read state of their own events only'
);

select throws_ok(
  $$ update public.events set seen_at = null $$,
  '42501', null,
  'an owner cannot write the read state directly'
);

select set_config('request.jwt.claims', '', true);

select throws_ok(
  $$ select public.acknowledge_events_v1('{9000001}') $$,
  '28000', 'not signed in',
  'a token without a user marks nothing'
);

reset role;
set local role anon;

select throws_ok(
  $$ select public.acknowledge_events_v1('{9000001}') $$,
  '42501', null,
  'anon cannot mark events'
);

reset role;

update public.events set seen_at = '2026-01-01T00:00:00Z' where id = 9000001;
update public.events set seen_at = null where id = 9000002;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select is(
  public.acknowledge_events_v1('{9000001,9000002}'),
  1,
  'acknowledging again marks only what was not seen'
);

reset role;

select results_eq(
  $$ select device_id::text, agent_event_id, seen_at is not null from public.events
     order by device_id, agent_event_id $$,
  $$ values ('00000000-0000-0000-0000-0000000000a1', 1::bigint, true),
            ('00000000-0000-0000-0000-0000000000a1', 2::bigint, true),
            ('00000000-0000-0000-0000-0000000000a1', 3::bigint, true),
            ('00000000-0000-0000-0000-0000000000b1', 1::bigint, false) $$,
  'only the caller''s events were marked'
);

select is(
  (select seen_at from public.events where id = 9000001),
  '2026-01-01T00:00:00Z'::timestamptz,
  'an event keeps the time it was first seen'
);

-- Names and rooms

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ update public.network_hosts set label = 'Kari''s TV', room = 'Living room' $$,
  'an owner names a host and gives it a room'
);

select lives_ok(
  $$ update public.network_hosts set label = repeat('x', 40), room = repeat('y', 32) $$,
  'a name of 40 and a room of 32 characters are accepted'
);

select throws_ok(
  $$ update public.network_hosts set label = repeat('x', 41) $$,
  '23514', null,
  'a name over 40 characters is refused'
);

select throws_ok(
  $$ update public.network_hosts set room = repeat('y', 33) $$,
  '23514', null,
  'a room over 32 characters is refused'
);

select throws_ok(
  $$ update public.network_hosts set label = '' $$,
  '23514', null,
  'an empty name is refused; null clears it'
);

select throws_ok(
  $$ update public.network_hosts set label = 'TV' || chr(10) || 'evil' $$,
  '23514', null,
  'a control character in a name is refused'
);

select throws_ok(
  $$ update public.network_hosts set room = 'Kitchen' || chr(27) || '[31m' $$,
  '23514', null,
  'a control character in a room is refused'
);

select lives_ok(
  $$ update public.network_hosts set label = 'Kari''s TV', room = null $$,
  'an owner clears a room'
);

select throws_ok(
  $$ update public.network_hosts set hostname = 'spoofed' $$,
  '42501', null,
  'an owner still cannot change what the device reported'
);

reset role;
set local role anon;

select throws_ok(
  $$ update public.network_hosts set label = 'anon' $$,
  '42501', null,
  'anon cannot name hosts'
);

reset role;

select is(
  public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
    '[{"mac": "3c:22:fb:00:00:0a", "ip": "192.168.1.11", "hostname": "LGwebOSTV"}]', '[]'),
  1,
  'a device report after naming is accepted'
);

select results_eq(
  $$ select mac::text, label, room from public.network_hosts order by mac $$,
  $$ values ('3c:22:fb:00:00:0a', 'Kari''s TV', null::text),
            ('3c:22:fb:00:00:0b', null, null) $$,
  'only the owner''s host is named, and device reports keep the name'
);

-- Monitoring status

set local role anon;

select throws_ok(
  $$ select public.device_heartbeat_v2('00000000-0000-0000-0000-0000000000a1', repeat('b', 64),
       '0.2.0', '{}', null, '[]', '[]') $$,
  '28000', 'device authentication failed',
  'v2: another device''s secret is refused'
);

select lives_ok(
  $$ select pg_temp.beat(
       $json$[
         {"name": "decoys", "state": "ok", "detail": {"decoys": 8}},
         {"name": "port_scan", "state": "ok"},
         {"name": "arp_watch", "state": "ok", "detail": {}},
         {"name": "dhcp_watch", "state": "starting"},
         {"name": "router_watch", "state": "ok"},
         {"name": "name_poisoning", "state": "failed"},
         {"name": "ids", "state": "ok", "detail": {"rules": 45213, "ruleset_date": "2026-11-02"}},
         {"name": "inventory", "state": "off", "detail": {"hosts": 23}}
       ]$json$,
       $json$[
         {"name": "canary", "version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"},
         {"name": "agent", "version": "0.2.0", "updated_at": "2026-11-02T04:32:00Z"},
         {"name": "ids", "version": "edge-0123456789ab", "updated_at": "2026-11-02T04:40:59Z"}
       ]$json$) $$,
  'v2 stores the monitors and images'
);

select lives_ok(
  $$ select public.device_heartbeat_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       '0.1.0', '{ssh}', '192.168.1.20') $$,
  'v1 keeps working next to v2'
);

select throws_ok(
  $$ select pg_temp.beat('{"name": "ids", "state": "ok"}') $$,
  '23514', null,
  'v2: monitors that are not an array are refused'
);

select throws_ok(
  $$ select pg_temp.beat(null) $$,
  '23502', null,
  'v2: missing monitors are refused'
);

select throws_ok(
  $$ select pg_temp.beat('[]', '{}') $$,
  '23514', null,
  'v2: images that are not an array are refused'
);

select throws_ok(
  $$ select pg_temp.beat((select jsonb_agg(jsonb_build_object('name', 'ids', 'state', 'ok'))
                           from generate_series(1, 17))) $$,
  '23514', null,
  'v2: more than 16 monitors are refused'
);

select throws_ok(
  $$ select pg_temp.beat('[{"name": "ids", "state": "ok"}, {"name": "ids", "state": "failed"}]') $$,
  '23514', null,
  'v2: a monitor reported twice is refused'
);

select throws_ok(
  $$ select pg_temp.beat('["ids"]') $$,
  '23514', null,
  'v2: a monitor that is not an object is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"name": "keylogger"}') $$,
  '23514', null,
  'v2: a monitor name outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"state": "great"}') $$,
  '23514', null,
  'v2: a state outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.beat('[{"name": "ids"}]') $$,
  '23514', null,
  'v2: a monitor without a state is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"message": "hello"}') $$,
  '23514', null,
  'v2: a field outside name, state and detail is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"note": "<b>hi</b>"}}') $$,
  '23514', null,
  'v2: free text in a detail is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"rules": "45213"}}') $$,
  '23514', null,
  'v2: a count given as text is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"hosts": -1}}') $$,
  '23514', null,
  'v2: a negative count is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"hosts": 1.5}}') $$,
  '23514', null,
  'v2: a fractional count is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"rules": 10000000}}') $$,
  '23514', null,
  'v2: a count over seven digits is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"ruleset_date": "2 Nov 2026"}}') $$,
  '23514', null,
  'v2: a date in another format is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": {"ruleset_date": "2026-02-30"}}') $$,
  '23514', null,
  'v2: an impossible date is refused'
);

select throws_ok(
  $$ select pg_temp.monitor('{"detail": null}') $$,
  '23514', null,
  'v2: a detail that is not an object is refused'
);

select throws_ok(
  $$ select pg_temp.beat('[]', (select jsonb_agg(jsonb_build_object(
       'name', 'ids', 'version', '0.2.0', 'updated_at', '2026-11-02T04:31:07Z'))
       from generate_series(1, 9))) $$,
  '23514', null,
  'v2: more than 8 images are refused'
);

select throws_ok(
  $$ select pg_temp.beat('[]', $json$[
       {"name": "ids", "version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"},
       {"name": "ids", "version": "0.1.0", "updated_at": "2026-11-01T04:31:07Z"}]$json$) $$,
  '23514', null,
  'v2: an image reported twice is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"name": "postgres"}') $$,
  '23514', null,
  'v2: an image name outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"version": "0.2.0; rm -rf"}') $$,
  '23514', null,
  'v2: a version outside [0-9A-Za-z.+-] is refused'
);

select throws_ok(
  $$ select pg_temp.image(jsonb_build_object('version', repeat('1', 33))) $$,
  '23514', null,
  'v2: a version over 32 characters is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"version": 2}') $$,
  '23514', null,
  'v2: a version given as a number is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"updated_at": "2026-11-02 04:31:07+01"}') $$,
  '23514', null,
  'v2: a build time not in UTC to the second is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"updated_at": "2026-13-02T04:31:07Z"}') $$,
  '23514', null,
  'v2: an impossible build time is refused'
);

select throws_ok(
  $$ select pg_temp.image('{"tag": "latest"}') $$,
  '23514', null,
  'v2: a field outside name, version and updated_at is refused'
);

reset role;

select results_eq(
  $$ select online, agent_version, last_seen_at = now(), jsonb_array_length(monitors),
            monitors -> 6, images -> 2 ->> 'version'
     from public.devices where id = '00000000-0000-0000-0000-0000000000a1' $$,
  $$ values (true, '0.1.0', true, 8,
             '{"name": "ids", "state": "ok", "detail": {"rules": 45213, "ruleset_date": "2026-11-02"}}'::jsonb,
             'edge-0123456789ab') $$,
  'v2 records the heartbeat; v1 afterwards keeps the last report; no malformed report was stored'
);

select is(
  (select count(*)::integer from public.events
   where device_id = '00000000-0000-0000-0000-0000000000a1' and kind = 'device_online'),
  1,
  'coming online through v2 is recorded once'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select results_eq(
  $$ select name, monitors::jsonb, images::jsonb from public.devices $$,
  $$ values ('B device', '[]'::jsonb, '[]'::jsonb) $$,
  'another owner reads only their own device, which has reported nothing yet'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select throws_ok(
  $$ update public.devices set monitors = '[]' $$,
  '42501', null,
  'an owner cannot write the monitoring report'
);

select lives_ok(
  $$ update public.devices set name = 'Renamed' $$,
  'an owner still renames a device that has reported monitors'
);

select * from finish();
rollback;

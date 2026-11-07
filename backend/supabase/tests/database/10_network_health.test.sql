-- Network health, exposure checks and the speed test (ADR 0015). Abuse cases
-- 1, 2, 4 and 5 for device_health_v1, its tables and the speed test setting.
begin;
create extension if not exists pgtap with schema extensions;
select plan(59);

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

insert into public.network_hosts (device_id, mac, ip, kind) values
  ('00000000-0000-0000-0000-0000000000a1', '7c:ed:8d:00:00:01', '192.168.1.40', 'game_console');

-- The hour that ended last, as the agent reports it.
create function pg_temp.last_hour(hours_ago integer default 1)
  returns timestamptz
  language sql
as $$
  select date_trunc('hour', now(), 'UTC') - make_interval(hours => hours_ago)
$$;

-- A report from device A with one gateway sample changed by sample, and the
-- given forwards.
create function pg_temp.report(sample jsonb, forwards jsonb default null)
  returns jsonb
  language sql
as $$
  select public.device_health_v1(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
    jsonb_build_array(
      jsonb_build_object(
        'hour', pg_temp.last_hour(), 'target', 'gateway', 'probes', 60, 'lost', 0,
        'median_ms', 1.2, 'worst_ms', 4.5, 'outage_minutes', 0
      ) || sample
    ),
    null, null, forwards, null
  )
$$;

-- A report from device A with only forwards.
create function pg_temp.forwards(forwards jsonb)
  returns jsonb
  language sql
as $$
  select public.device_health_v1(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '[]', null, null, forwards, null
  )
$$;

-- A report from device A with only a DNS result.
create function pg_temp.dns(result text)
  returns jsonb
  language sql
as $$
  select public.device_health_v1(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '[]', result, '192.168.1.1', null, null
  )
$$;

-- A report from device A with only a speed test result.
create function pg_temp.speed(mbps numeric)
  returns jsonb
  language sql
as $$
  select public.device_health_v1(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '[]', null, null, null, mbps
  )
$$;

grant execute on function pg_temp.last_hour(integer) to anon;
grant execute on function pg_temp.report(jsonb, jsonb) to anon;
grant execute on function pg_temp.forwards(jsonb) to anon;
grant execute on function pg_temp.dns(text) to anon;
grant execute on function pg_temp.speed(numeric) to anon;

set local role anon;

-- Authentication and shape.

select throws_ok(
  $$ select public.device_health_v1('00000000-0000-0000-0000-0000000000a1', repeat('b', 64),
       '[]', null, null, null, null) $$,
  '28000', 'device authentication failed',
  'another device''s secret is refused'
);

select is(
  pg_temp.report('{}'),
  '{"speed_test": false}'::jsonb,
  'a report is stored and the reply carries the speed test setting'
);

select is(
  pg_temp.report('{"lost": 2, "median_ms": 1.5}'),
  '{"speed_test": false}'::jsonb,
  'a resent hour replaces the stored one'
);

select throws_ok(
  $$ select public.device_health_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
       {"hour": "2020-01-01T00:00:00Z", "target": "internet", "probes": 1, "lost": 1, "outage_minutes": 0}
     ]$json$, null, null, null, null) $$,
  '22023', 'invalid argument',
  'a sample older than 7 days is refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('hour', pg_temp.last_hour(-2))) $$,
  '22023', 'invalid argument',
  'a sample from the future is refused'
);

select throws_ok(
  $$ select public.device_health_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       '{}', null, null, null, null) $$,
  '22023', 'invalid argument',
  'samples that are not an array are refused'
);

select throws_ok(
  $$ select public.device_health_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       (select jsonb_agg(jsonb_build_object('hour', pg_temp.last_hour(n), 'target', 'gateway',
          'probes', 1, 'lost', 1, 'outage_minutes', 0)) from generate_series(1, 217) as n),
       null, null, null, null) $$,
  '22023', 'invalid argument',
  'more than 216 samples are refused'
);

select throws_ok(
  $$ select public.device_health_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       (select jsonb_agg(jsonb_build_object('hour', pg_temp.last_hour(), 'target', 'gateway',
          'probes', 1, 'lost', 1, 'outage_minutes', 0)) from generate_series(1, 2)),
       null, null, null, null) $$,
  '22023', 'invalid argument',
  'an hour reported twice for one target is refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('hour', pg_temp.last_hour() + interval '1 minute')) $$,
  '23514', null,
  'a sample not on the hour is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"target": "neighbour"}') $$,
  '23514', null,
  'a target outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"probes": 61}') $$,
  '23514', null,
  'more than 60 probes in an hour are refused'
);

select throws_ok(
  $$ select pg_temp.report('{"lost": 61}') $$,
  '23514', null,
  'more lost probes than sent are refused'
);

select throws_ok(
  $$ select pg_temp.report('{"outage_minutes": 1}') $$,
  '23514', null,
  'outage minutes without lost probes are refused'
);

select throws_ok(
  $$ select pg_temp.report('{"median_ms": "NaN"}') $$,
  '23514', null,
  'a latency that is not a number is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"worst_ms": 0.5}') $$,
  '23514', null,
  'a worst latency below the median is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"lost": 60}') $$,
  '23514', null,
  'a latency for an hour without answers is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"probes": 70000}') $$,
  '22003', null,
  'a number beyond its type is refused'
);

-- Port forwards.

select is(
  pg_temp.forwards($json$[
    {"protocol": "udp", "external_port": 3074, "internal_client": "192.168.1.40", "internal_port": 3074,
     "description": "Xbox"},
    {"protocol": "tcp", "external_port": 8080, "internal_client": "192.168.1.77", "internal_port": 80}
  ]$json$) ->> 'speed_test',
  'false',
  'forwards are stored'
);

reset role;

select results_eq(
  $$ select host(src_ip), src_mac::text, dst_port, detail ->> 'protocol', severity
     from public.events where kind = 'port_forward' order by dst_port $$,
  $$ values ('192.168.1.40', '7c:ed:8d:00:00:01', 3074, 'udp', 'warning'),
            ('192.168.1.77', null, 8080, 'tcp', 'warning') $$,
  'each new mapping is a warning naming the device it leads to'
);

set local role anon;

select lives_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "udp", "external_port": 3074, "internal_client": "192.168.1.40", "internal_port": 3075}
     ]$json$) $$,
  'a known mapping is reported again'
);

select lives_ok(
  $$ select pg_temp.forwards(null) $$,
  'a report without forwards is accepted'
);

reset role;

select results_eq(
  $$ select external_port, open from public.port_forwards order by external_port $$,
  $$ values (3074, true), (8080, false) $$,
  'a mapping missing from the latest read is closed; a report without forwards changes nothing'
);

set local role anon;

select lives_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "udp", "external_port": 3074, "internal_client": "192.168.1.40", "internal_port": 3075},
       {"protocol": "tcp", "external_port": 8080, "internal_client": "192.168.1.77", "internal_port": 80}
     ]$json$) $$,
  'a mapping that closed opens again'
);

reset role;

select is(
  (select count(*)::integer from public.events where kind = 'port_forward'),
  2,
  'a known mapping is not new, even after it closed and opened again'
);

set local role anon;

select throws_ok(
  $$ select pg_temp.forwards((select jsonb_agg(jsonb_build_object('protocol', 'tcp',
       'external_port', n, 'internal_client', '192.168.1.40', 'internal_port', n))
       from generate_series(1, 65) as n)) $$,
  '22023', 'invalid argument',
  'more than 64 forwards are refused'
);

select throws_ok(
  $$ select pg_temp.forwards('{"protocol": "tcp"}') $$,
  '22023', 'invalid argument',
  'forwards that are not an array are refused'
);

select throws_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "tcp", "external_port": 80, "internal_client": "192.168.1.40", "internal_port": 80},
       {"protocol": "tcp", "external_port": 80, "internal_client": "192.168.1.40", "internal_port": 81}
     ]$json$) $$,
  '22023', 'invalid argument',
  'a mapping reported twice is refused'
);

select throws_ok(
  $$ select pg_temp.forwards(jsonb_build_array(jsonb_build_object('protocol', 'tcp',
       'external_port', 80, 'internal_client', '192.168.1.40', 'internal_port', 80,
       'description', 'Web' || chr(27) || '[31m'))) $$,
  '23514', null,
  'control characters in a description are refused'
);

select throws_ok(
  $$ select pg_temp.forwards(jsonb_build_array(jsonb_build_object('protocol', 'tcp',
       'external_port', 80, 'internal_client', '192.168.1.40', 'internal_port', 80,
       'description', repeat('x', 65)))) $$,
  '23514', null,
  'an oversized description is refused'
);

select throws_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "tcp", "external_port": 80, "internal_client": "192.168.1.0/24", "internal_port": 80}
     ]$json$) $$,
  '23514', null,
  'a mapping to a network instead of one address is refused'
);

select throws_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "sctp", "external_port": 80, "internal_client": "192.168.1.40", "internal_port": 80}
     ]$json$) $$,
  '23514', null,
  'a protocol outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.forwards($json$[
       {"protocol": "tcp", "external_port": 65536, "internal_client": "192.168.1.40", "internal_port": 80}
     ]$json$) $$,
  '23514', null,
  'a port outside 0 to 65535 is refused'
);

-- DNS integrity.

select lives_ok($$ select pg_temp.dns('ok') $$, 'a matching DNS check is stored');
select lives_ok($$ select pg_temp.dns('mismatch') $$, 'a mismatch is stored');
select lives_ok($$ select pg_temp.dns('mismatch') $$, 'a mismatch is reported again');
select lives_ok($$ select pg_temp.dns(null) $$, 'a report without a DNS result keeps the last one');

select throws_ok(
  $$ select pg_temp.dns('fine') $$,
  '23514', null,
  'a DNS result outside the vocabulary is refused'
);

-- Speed test.

select is(pg_temp.speed(93.4), '{"speed_test": false}'::jsonb, 'a result while the test is off is ignored');

reset role;

select results_eq(
  $$ select hour, target, probes::integer, lost::integer, median_ms, worst_ms
     from public.health_samples $$,
  $$ values (pg_temp.last_hour(), 'gateway', 60, 2, 1.5::real, 4.5::real) $$,
  'only the valid samples are stored, a resent hour once'
);

select results_eq(
  $$ select protocol, external_port, host(internal_client), internal_port, description, open
     from public.port_forwards order by external_port $$,
  $$ values ('udp', 3074, '192.168.1.40', 3075, null::text, true),
            ('tcp', 8080, '192.168.1.77', 80, null, true) $$,
  'forwards follow the latest read'
);

select results_eq(
  $$ select dns_check, host(dns_resolver), dns_checked_at is not null, forwards_checked_at is not null
     from public.devices where id = '00000000-0000-0000-0000-0000000000a1' $$,
  $$ values ('mismatch', '192.168.1.1', true, true) $$,
  'the device keeps the latest DNS result and when its forwards were read'
);

select results_eq(
  $$ select host(src_ip), src_mac, severity from public.events where kind = 'dns_hijack' $$,
  $$ values ('192.168.1.1', null::macaddr, 'critical') $$,
  'a change to mismatch is one critical event without a source device'
);

select is((select count(*)::integer from public.speed_tests), 0, 'no speed test is stored while it is off');

-- The address a report came from, as Cloudflare sets it.

select set_config('request.headers',
  '{"cf-connecting-ip": "203.0.113.7", "cf-ipcountry": "NO", "x-forwarded-for": "198.51.100.1, 203.0.113.7"}',
  true);
select pg_temp.forwards(null);
select results_eq(
  $$ select host(public_ip), public_country from public.devices
     where id = '00000000-0000-0000-0000-0000000000a1' $$,
  $$ values ('203.0.113.7', 'NO') $$,
  'the public address and country come from cf-connecting-ip and cf-ipcountry'
);

select set_config('request.headers',
  '{"cf-connecting-ip": "203.0.113.7/8", "cf-ipcountry": "XX", "x-forwarded-for": "198.51.100.1"}', true);
select pg_temp.forwards(null);
select results_eq(
  $$ select public_ip, public_country from public.devices
     where id = '00000000-0000-0000-0000-0000000000a1' $$,
  $$ values (null::inet, null::text) $$,
  'a malformed address and an unknown country are not stored, and X-Forwarded-For is never used'
);

select set_config('request.headers', '{"cf-connecting-ip": "2001:db8::7", "cf-ipcountry": "T1"}', true);
select pg_temp.forwards(null);
select results_eq(
  $$ select host(public_ip), public_country from public.devices
     where id = '00000000-0000-0000-0000-0000000000a1' $$,
  $$ values ('2001:db8::7', null::text) $$,
  'an IPv6 address is stored; Tor is not a country'
);

select set_config('request.headers', '', true);

-- The owner's side.

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ update public.devices set speed_test = true $$,
  'an owner may turn the speed test on'
);

select is(
  (select count(*)::integer from public.devices where speed_test),
  1,
  'only the owner''s own device changed'
);

select throws_ok(
  $$ update public.devices set public_ip = '192.0.2.1' $$,
  '42501', null,
  'an owner cannot change the recorded public address'
);

select throws_ok(
  $$ update public.port_forwards set open = false $$,
  '42501', null,
  'an owner cannot change forwards'
);

select is(
  (select count(*)::integer from public.port_forwards) + (select count(*)::integer from public.health_samples),
  3,
  'an owner reads their own forwards and samples'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select is(
  (select count(*)::integer from public.port_forwards)
    + (select count(*)::integer from public.health_samples)
    + (select count(*)::integer from public.speed_tests),
  0,
  'another owner reads none of them'
);

reset role;
set local role anon;

select is(pg_temp.speed(93.4), '{"speed_test": false}'::jsonb, 'a result while the test is on is stored');
select is(pg_temp.speed(12), '{"speed_test": false}'::jsonb, 'a second result within 20 hours is ignored');

select throws_ok(
  $$ select count(*) from public.speed_tests $$,
  '42501', null,
  'anon cannot read speed tests'
);

reset role;

select results_eq(
  $$ select download_mbps from public.speed_tests $$,
  $$ values (93.4::numeric) $$,
  'one result is stored per 20 hours'
);

update public.speed_tests set tested_at = now() - interval '21 hours';
set local role anon;
select is(pg_temp.report('{}'), '{"speed_test": true}'::jsonb, 'the reply asks for a test once the last is 20 hours old');
reset role;

-- Retention.

insert into public.health_samples (device_id, hour, target, probes, lost, outage_minutes) values
  ('00000000-0000-0000-0000-0000000000a1', pg_temp.last_hour(91 * 24), 'internet', 1, 1, 0);
insert into public.port_forwards (device_id, protocol, external_port, internal_client, internal_port, last_seen_at) values
  ('00000000-0000-0000-0000-0000000000a1', 'tcp', 22, '192.168.1.9', 22, now() - interval '91 days');
insert into public.speed_tests (device_id, tested_at, download_mbps) values
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '91 days', 50);

select private.apply_retention();

select is(
  (select count(*)::integer from public.health_samples where hour < now() - interval '90 days')
    + (select count(*)::integer from public.port_forwards where last_seen_at < now() - interval '90 days')
    + (select count(*)::integer from public.speed_tests where tested_at < now() - interval '90 days'),
  0,
  'samples, forwards and speed tests older than 90 days are removed'
);

select is(
  (select count(*)::integer from public.port_forwards),
  2,
  'recent forwards stay'
);

select * from finish();
rollback;

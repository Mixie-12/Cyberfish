-- Request limits in PostgREST's pre-request function (ADR 0016).
begin;
create extension if not exists pgtap with schema extensions;
select plan(23);

-- What PostgREST sets before it calls the pre-request function.
create function pg_temp.request(method text, path text, ip text, sub text default null)
  returns void
  language sql
as $$
  select
    set_config('request.method', method, true),
    set_config('request.path', path, true),
    set_config(
      'request.headers',
      jsonb_strip_nulls(jsonb_build_object('cf-connecting-ip', ip, 'x-forwarded-for', '198.51.100.1'))::text,
      true
    ),
    set_config(
      'request.jwt.claims',
      jsonb_strip_nulls(jsonb_build_object('role', current_user, 'sub', sub))::text,
      true
    )
$$;

-- PostgREST runs reads (GET, HEAD, POST to a stable function) read-only. The
-- exception undoes the setting, so the rest of the test can write again.
create function pg_temp.check_read_only(calls integer)
  returns void
  language plpgsql
as $$
begin
  set local transaction_read_only = on;
  perform guard.check_request() from generate_series(1, calls);
  raise exception 'not counted' using errcode = 'P0001';
end;
$$;

grant execute on function pg_temp.request(text, text, text, text) to anon, authenticated, service_role;
grant execute on function pg_temp.check_read_only(integer) to anon;

set local role anon;

select pg_temp.request('GET', '/devices', '203.0.113.1');
select throws_ok(
  $$ select pg_temp.check_read_only(700) $$,
  'P0001', 'not counted',
  'reads are never limited'
);

select pg_temp.request('POST', '/rpc/a_stable_function', '203.0.113.1');
select throws_ok(
  $$ select pg_temp.check_read_only(700) $$,
  'P0001', 'not counted',
  'a POST that PostgREST runs read-only is not counted and does not fail'
);

select pg_temp.request('POST', '/rpc/device_events_v1', '203.0.113.1');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 600) $$,
  'an address may make 600 writes a minute'
);

select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'the 601st write in the minute is refused'
);

select pg_temp.request('POST', '/rpc/device_events_v1', '203.0.113.2');
select lives_ok($$ select guard.check_request() $$, 'another address has its own count');

-- X-Forwarded-For begins with what the caller sent; it must not matter.
select set_config(
  'request.headers',
  '{"cf-connecting-ip": "203.0.113.1", "x-forwarded-for": "192.0.2.77, 203.0.113.1"}',
  true
);
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'a forged X-Forwarded-For does not escape the limit'
);

select pg_temp.request('POST', '/rpc/device_events_v1', '2001:db8:1:1::1');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 600) $$,
  'an IPv6 address may make 600 writes a minute'
);
select pg_temp.request('POST', '/rpc/device_events_v1', '2001:db8:1:1:ffff::2');
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'addresses in the same IPv6 /64 share one count'
);
select pg_temp.request('POST', '/rpc/device_events_v1', '2001:db8:1:2::1');
select lives_ok($$ select guard.check_request() $$, 'another /64 has its own count');

select pg_temp.request('POST', '/rpc/device_events_v1', 'not an address');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 700) $$,
  'without a valid platform address only the other limits apply'
);

select pg_temp.request('POST', '/rpc/device_register_v1', '203.0.113.3');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 20) $$,
  'an address may register 20 devices an hour'
);
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'the 21st registration in the hour is refused'
);
select pg_temp.request('POST', '/rpc/device_heartbeat_v1', '203.0.113.3');
select lives_ok($$ select guard.check_request() $$, 'other device calls from that address go on');

reset role;
set local role authenticated;

select pg_temp.request('POST', '/rpc/claim_device_v1', '203.0.113.4', '00000000-0000-0000-0000-00000000000a');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 20) $$,
  'a user may claim 20 times an hour'
);
select pg_temp.request('POST', '/rpc/claim_device_v1', '203.0.113.5', '00000000-0000-0000-0000-00000000000a');
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'the 21st is refused, from any address'
);

select pg_temp.request('POST', '/rpc/claim_device_v1', '203.0.113.4', '00000000-0000-0000-0000-00000000000b');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 10) $$,
  'another user behind the same address may claim'
);
select pg_temp.request('POST', '/rpc/claim_device_v1', '203.0.113.4', '00000000-0000-0000-0000-00000000000c');
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'an address may claim 30 times an hour'
);

select pg_temp.request('PATCH', '/devices', null, '00000000-0000-0000-0000-00000000000d');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 120) $$,
  'a user may make 120 writes a minute'
);
select throws_ok(
  $$ select guard.check_request() $$,
  'PT429', 'too many requests',
  'the 121st write in the minute is refused'
);

reset role;
set local role service_role;

select pg_temp.request('POST', '/rpc/notification_targets_v2', '203.0.113.1');
select lives_ok(
  $$ select guard.check_request() from generate_series(1, 700) $$,
  'the backend''s own calls are never limited'
);

reset role;

insert into private.rate_counters (bucket, window_end, hits) values ('ip write 192.0.2.1/32', now() - interval '1 second', 5);
select private.prune_rate_counters();
select ok(
  not exists (select 1 from private.rate_counters where window_end < now())
    and exists (select 1 from private.rate_counters where bucket = 'ip write 203.0.113.1/32'),
  'pruning removes ended windows and keeps current ones'
);

select ok(
  (
    select setconfig @> array[
      'pgrst.db_pre_request=guard.check_request',
      'pgrst.openapi_mode=disabled',
      'pgrst.db_aggregates_enabled=false',
      'pgrst.db_plan_enabled=false'
    ]
    from pg_db_role_setting
    where setrole = 'authenticator'::regrole and setdatabase = 0
  )
    and has_function_privilege('anon', 'guard.check_request()', 'execute')
    and has_function_privilege('authenticated', 'guard.check_request()', 'execute')
    and has_function_privilege('service_role', 'guard.check_request()', 'execute'),
  'PostgREST runs the check for every API role, without OpenAPI, aggregates or plans'
);

select results_eq(
  $$ select schedule::text from cron.job where jobname = 'dekoi-rate-counters' $$,
  $$ values ('*/10 * * * *') $$,
  'ended windows are pruned every 10 minutes'
);

select * from finish();
rollback;

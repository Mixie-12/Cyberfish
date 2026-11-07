-- The header check that the monitor workflow calls through the public API:
-- anyone may call it, and it says only whether the platform's headers
-- arrived, never what they hold.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

set local role anon;

select set_config(
  'request.headers',
  '{"cf-connecting-ip": "203.0.113.9", "cf-ipcountry": "NO", "x-forwarded-for": "192.0.2.1"}',
  true
);
select is(
  public.self_check_v1(),
  '{"client_address": true, "country": true}'::jsonb,
  'the platform''s address and country headers are both found'
);

select ok(
  position('203.0.113.9' in public.self_check_v1()::text) = 0
    and position('NO' in public.self_check_v1()::text) = 0,
  'the reply holds neither the address nor the country'
);

select set_config('request.headers', '{"x-forwarded-for": "192.0.2.1"}', true);
select is(
  public.self_check_v1(),
  '{"client_address": false, "country": false}'::jsonb,
  'without the platform''s headers both are false, whatever X-Forwarded-For says'
);

select set_config('request.headers', '{"cf-connecting-ip": "not an address", "cf-ipcountry": "no"}', true);
select is(
  public.self_check_v1(),
  '{"client_address": false, "country": false}'::jsonb,
  'a malformed address or country counts as missing'
);

select set_config('request.headers', '', true);
select is(
  public.self_check_v1(),
  '{"client_address": false, "country": false}'::jsonb,
  'outside a Data API request both are false'
);

reset role;

select ok(
  has_function_privilege('anon', 'public.self_check_v1()', 'execute')
    and not has_function_privilege('authenticated', 'public.self_check_v1()', 'execute')
    and not has_function_privilege('service_role', 'public.self_check_v1()', 'execute'),
  'only anon may call the header check'
);

select is(
  (select provolatile from pg_proc where oid = 'public.self_check_v1()'::regprocedure),
  's'::"char",
  'stable, so PostgREST runs it in a read-only transaction'
);

select * from finish();
rollback;

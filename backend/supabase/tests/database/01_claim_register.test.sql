-- Pairing: claim in the app, register from the device. Abuse case 3.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

-- User A
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || '1', 'DeKoi 0001') $$,
  'a signed-in user can claim a new code'
);

select is((select count(*)::integer from public.devices), 1, 'the claimed device is visible to its owner');

select is(
  public.claim_device_v1('C' || repeat('0', 24) || '1', 'Another name'),
  (select id from public.devices where name = 'DeKoi 0001'),
  'claiming the same code again returns the same device'
);

select lives_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || n::text, 'DeKoi') from generate_series(2, 5) as n $$,
  'up to five devices can wait for setup'
);

select throws_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || '6', 'DeKoi') $$,
  'PT429', 'too many devices waiting for setup',
  'a sixth unfinished setup is refused'
);

select throws_ok(
  $$ select public.claim_device_v1('not-a-code', 'DeKoi') $$,
  '22023', 'invalid claim code',
  'a malformed code is refused'
);

-- User B
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select throws_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || '1', 'Mine now') $$,
  '22023', 'invalid claim code',
  'a code claimed by another account looks like any invalid code'
);

select is((select count(*)::integer from public.devices), 0, 'another account sees none of the devices');

-- The device
reset role;
set local role anon;

select throws_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || '7', 'DeKoi') $$,
  '42501', null,
  'anonymous callers cannot claim'
);

select isnt(
  public.device_register_v1('C' || repeat('0', 24) || '1', repeat('a', 64), '0.1.0'),
  null,
  'a device registers with a claimed code'
);

select is(
  public.device_register_v1('C' || repeat('0', 24) || '1', repeat('a', 64), '0.1.0'),
  public.device_register_v1('C' || repeat('0', 24) || '1', repeat('a', 64), '0.1.0'),
  'repeating a registration with the same secret is idempotent'
);

select throws_ok(
  $$ select public.device_register_v1('C' || repeat('0', 24) || '1', repeat('b', 64), '0.1.0') $$,
  '28000', 'registration refused',
  'a second device cannot register with a used code'
);

select throws_ok(
  $$ select public.device_register_v1('C' || repeat('0', 24) || '9', repeat('a', 64), '0.1.0') $$,
  '28000', 'registration refused',
  'an unclaimed code cannot register'
);

select throws_ok(
  $$ select public.device_register_v1('C' || repeat('0', 24) || '2', 'short', '0.1.0') $$,
  '22023', 'invalid argument',
  'a malformed secret is refused'
);

reset role;

select ok(
  (select registered_at is not null and agent_version = '0.1.0' from public.devices where name = 'DeKoi 0001'),
  'registration marks the device registered and records the agent version'
);

select ok(
  not exists (
    select 1 from private.device_credentials
    where secret_hash = convert_to(repeat('a', 64), 'UTF8')
       or claim_code_hash = convert_to('C' || repeat('0', 24) || '1', 'UTF8')
  ),
  'codes and secrets are stored only as hashes'
);

-- A registered device no longer counts towards the pending limit.
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ select public.claim_device_v1('C' || repeat('0', 24) || '6', 'DeKoi') $$,
  'finishing a setup frees a pending slot'
);

select * from finish();
rollback;

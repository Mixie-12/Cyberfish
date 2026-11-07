-- Push tokens and account deletion.
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B device');

insert into private.device_credentials (device_id, claim_code_hash) values
  ('00000000-0000-0000-0000-0000000000a1', private.sha256_text('C' || repeat('0', 24) || 'A')),
  ('00000000-0000-0000-0000-0000000000b1', private.sha256_text('C' || repeat('0', 24) || 'B'));

insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000a1', now(), 'device_online'),
  ('00000000-0000-0000-0000-0000000000b1', now(), 'device_online');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.register_push_token_v1('phone-token') $$, 'A registers a push token');

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);
select lives_ok($$ select public.register_push_token_v1('phone-token') $$, 'B signs in on the same phone');

reset role;
select results_eq(
  'select user_id from private.push_tokens',
  $$ values ('00000000-0000-0000-0000-00000000000b'::uuid) $$,
  'the token moves to the account that registered it last'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.unregister_push_token_v1('phone-token') $$, 'A tries to remove B''s token');

reset role;
select is((select count(*)::integer from private.push_tokens), 1, 'a token can only be removed by its owner');

set local role anon;
select throws_ok($$ select public.delete_account_v1() $$, '42501', null, 'anon cannot delete accounts');

reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.delete_account_v1() $$, 'A deletes A''s account');

reset role;

select ok(
  not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from public.devices where owner_id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from private.device_credentials where device_id = '00000000-0000-0000-0000-0000000000a1'),
  'deleting an account removes all of its data'
);

select ok(
  exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000b')
    and exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000b1')
    and exists (select 1 from private.push_tokens where user_id = '00000000-0000-0000-0000-00000000000b'),
  'other accounts are untouched'
);

select * from finish();
rollback;

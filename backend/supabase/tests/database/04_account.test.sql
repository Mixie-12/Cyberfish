-- Push tokens and account deletion: deleting the account removes every row
-- about it (docs/security.md, "Personal data").
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

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

insert into public.network_hosts (device_id, mac, ip) values
  ('00000000-0000-0000-0000-0000000000a1', '3c:22:fb:00:00:0a', '192.168.1.10');
insert into public.networks (device_id, cidr, source) values
  ('00000000-0000-0000-0000-0000000000a1', '192.168.1.0/24', 'interface');
insert into private.notified_sources (device_id, source, notified_at) values
  ('00000000-0000-0000-0000-0000000000a1', '192.168.1.50', now());
insert into auth.sessions (id, user_id) values
  ('00000000-0000-0000-0000-0000000005a1', '00000000-0000-0000-0000-00000000000a');
insert into private.push_tokens (token, user_id) values
  ('a-tablet', '00000000-0000-0000-0000-00000000000a');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.register_push_token_v1('phone-token') $$, 'A registers a push token');

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);
select lives_ok($$ select public.register_push_token_v1('phone-token') $$, 'B signs in on the same phone');

reset role;
select results_eq(
  $$ select user_id from private.push_tokens where token = 'phone-token' $$,
  $$ values ('00000000-0000-0000-0000-00000000000b'::uuid) $$,
  'the token moves to the account that registered it last'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.unregister_push_token_v1('phone-token') $$, 'A tries to remove B''s token');

reset role;
select is((select count(*)::integer from private.push_tokens where token = 'phone-token'), 1, 'a token can only be removed by its owner');

set local role anon;
select throws_ok($$ select public.delete_account_v1() $$, '42501', null, 'anon cannot delete accounts');

reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.delete_account_v1() $$, 'A deletes A''s account');

reset role;

select ok(
  not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from auth.sessions where user_id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from private.push_tokens where user_id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from public.devices where owner_id = '00000000-0000-0000-0000-00000000000a')
    and not exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from public.network_hosts where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from public.networks where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from private.notified_sources where device_id = '00000000-0000-0000-0000-0000000000a1')
    and not exists (select 1 from private.device_credentials where device_id = '00000000-0000-0000-0000-0000000000a1'),
  'deleting an account removes all of its data'
);

select ok(
  exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000b')
    and exists (select 1 from public.events where device_id = '00000000-0000-0000-0000-0000000000b1')
    and exists (select 1 from private.push_tokens where user_id = '00000000-0000-0000-0000-00000000000b'),
  'other accounts are untouched'
);

-- A table added later is covered only if it is removed with its account:
-- through foreign keys with on delete cascade, directly or through another
-- such table. Rate counts hold no reference and end within 70 minutes.
select is_empty(
  $$
    with recursive removed (rel) as (
      select 'auth.users'::regclass::oid
      union
      select c.conrelid
      from pg_constraint as c
      join removed as r on c.confrelid = r.rel
      where c.contype = 'f' and c.confdeltype = 'c'
    )
    select c.oid::regclass::text
    from pg_class as c
    join pg_namespace as n on n.oid = c.relnamespace
    where n.nspname in ('public', 'private')
      and c.relkind in ('r', 'p')
      and c.oid not in (select rel from removed)
      and c.oid <> 'private.rate_counters'::regclass
  $$,
  'every table in public and private is removed with the account'
);

select * from finish();
rollback;

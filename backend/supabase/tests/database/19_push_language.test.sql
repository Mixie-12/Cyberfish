-- The language of a phone's push text (ADR 0024): register_push_token_v2,
-- v1 as English, and the language in notification_targets_v3 and
-- weekly_summary_v2.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name, registered_at) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device', now() - interval '30 days');

insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000a1', now(), 'device_offline');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ select public.register_push_token_v2('a-phone', 'nb') $$,
  'an owner registers a phone in Norwegian'
);

select lives_ok(
  $$ select public.register_push_token_v2('a-tablet', 'en') $$,
  'and one in English'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select lives_ok(
  $$ select public.register_push_token_v2(v.token, v.language)
     from (values ('b-1', 'de'), ('b-2', 'NB'), ('b-3', ''), ('b-4', null), ('b-5', 'nb''); drop table public.events; --'))
       as v (token, language) $$,
  'a language without templates does not stop a phone from registering'
);

reset role;

select results_eq(
  $$ select token, language from private.push_tokens order by token $$,
  $$ values ('a-phone', 'nb'), ('a-tablet', 'en'), ('b-1', 'en'), ('b-2', 'en'), ('b-3', 'en'), ('b-4', 'en'), ('b-5', 'en') $$,
  'each phone has its language, and anything outside the list is stored as English'
);

select throws_ok(
  $$ update private.push_tokens set language = 'de' where token = 'a-phone' $$,
  '23514', null,
  'the table holds only languages notify has templates for'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.register_push_token_v1('a-phone') $$, 'an older app registers the same phone with v1');
reset role;

select is(
  (select language from private.push_tokens where token = 'a-phone'),
  'en',
  'v1 stores English'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select lives_ok($$ select public.register_push_token_v2('a-phone', 'nb') $$, 'the updated app registers it again with v2');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);
select lives_ok($$ select public.register_push_token_v2('a-tablet', 'nb') $$, 'B signs in on A''s tablet in Norwegian');
reset role;

select results_eq(
  $$ select token, user_id, language from private.push_tokens where token like 'a-%' order by token $$,
  $$ values ('a-phone', '00000000-0000-0000-0000-00000000000a'::uuid, 'nb'),
            ('a-tablet', '00000000-0000-0000-0000-00000000000b'::uuid, 'nb') $$,
  'registering again sets the language, and a token moves with its language to the last account'
);

set local role anon;
select throws_ok(
  $$ select public.register_push_token_v2('anon-phone', 'nb') $$,
  '42501', null,
  'anonymous callers cannot register'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '', true);
select throws_ok(
  $$ select public.register_push_token_v2('no-one', 'nb') $$,
  '28000', 'not signed in',
  'a request without a user cannot register'
);
reset role;

select results_eq(
  $$ select t.token, t.template, t.severity, t.host_kind, t.language
     from public.notification_targets_v3(
       (select e.id from public.events as e where e.device_id = '00000000-0000-0000-0000-0000000000a1')
     ) as t $$,
  $$ values ('a-phone', 'device_offline', 'warning', null::text, 'nb') $$,
  'notification_targets_v3 returns v2''s targets with each phone''s language'
);

select results_eq(
  $$ select s.token, s.language from public.weekly_summary_v2('00000000-0000-0000-0000-00000000000a') as s $$,
  $$ values ('a-phone', 'nb') $$,
  'weekly_summary_v2 returns v1''s summary with each phone''s language'
);

select is_empty(
  $$ select * from public.weekly_summary_v2('00000000-0000-0000-0000-00000000000a') $$,
  'v1 still records the summary, so a second request sends nothing'
);

select ok(
  has_function_privilege('authenticated', 'public.register_push_token_v2(text, text)', 'execute')
    and not has_function_privilege('anon', 'public.register_push_token_v2(text, text)', 'execute'),
  'only signed-in users may register'
);

select ok(
  has_function_privilege('service_role', 'public.notification_targets_v3(bigint)', 'execute')
    and has_function_privilege('service_role', 'public.weekly_summary_v2(uuid)', 'execute')
    and not has_function_privilege('anon', 'public.notification_targets_v3(bigint)', 'execute')
    and not has_function_privilege('authenticated', 'public.notification_targets_v3(bigint)', 'execute')
    and not has_function_privilege('anon', 'public.weekly_summary_v2(uuid)', 'execute')
    and not has_function_privilege('authenticated', 'public.weekly_summary_v2(uuid)', 'execute'),
  'only the notify function may ask for targets and summaries'
);

select * from finish();
rollback;

-- The weekly summary (ADR 0015): the owner setting and its RLS, the counts,
-- once a week, and the job that asks the notify function to send it.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid'),
  ('00000000-0000-0000-0000-00000000000c', 'c@test.invalid');

insert into public.devices (id, owner_id, name, registered_at) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A old', now() - interval '30 days'),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000000a', 'A new', now() - interval '3 days'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B', now() - interval '30 days'),
  ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000000c', 'C waiting', null);

insert into private.push_tokens (token, user_id) values
  ('a-phone', '00000000-0000-0000-0000-00000000000a'),
  ('a-tablet', '00000000-0000-0000-0000-00000000000a'),
  ('b-phone', '00000000-0000-0000-0000-00000000000b'),
  ('c-phone', '00000000-0000-0000-0000-00000000000c');

-- The owner setting.

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  'insert into public.owner_settings (weekly_summary) values (false)',
  'an owner stores their own setting'
);

select results_eq(
  'select user_id, weekly_summary from public.owner_settings',
  $$ values ('00000000-0000-0000-0000-00000000000a'::uuid, false) $$,
  'the row belongs to the caller and the caller reads it'
);

select lives_ok(
  $$ insert into public.owner_settings (weekly_summary) values (true)
     on conflict (user_id) do update set weekly_summary = excluded.weekly_summary $$,
  'an owner changes their setting with an upsert, as the app does'
);

select results_eq(
  'select weekly_summary from public.owner_settings',
  'values (true)',
  'the upsert changed the owner''s row'
);

select throws_ok(
  $$ insert into public.owner_settings (user_id, weekly_summary)
     values ('00000000-0000-0000-0000-00000000000b', false) $$,
  '42501', null,
  'an owner cannot write a setting for someone else'
);

select throws_ok(
  $$ update public.owner_settings set user_id = '00000000-0000-0000-0000-00000000000b' $$,
  '42501', null,
  'an owner cannot move their setting to someone else'
);

select throws_ok(
  'delete from public.owner_settings',
  '42501', null,
  'settings are not deleted by the app; they go with the account'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select is_empty(
  'select * from public.owner_settings',
  'another owner cannot read the setting'
);

select is_empty(
  'update public.owner_settings set weekly_summary = false returning user_id',
  'another owner cannot change the setting'
);

select lives_ok(
  'insert into public.owner_settings (weekly_summary) values (false)',
  'another owner stores their own setting next to it'
);

set local role anon;

select throws_ok(
  'select * from public.owner_settings',
  '42501', null,
  'anonymous callers cannot read settings'
);

reset role;

select results_eq(
  'select user_id, weekly_summary from public.owner_settings order by user_id',
  $$ values ('00000000-0000-0000-0000-00000000000a'::uuid, true),
            ('00000000-0000-0000-0000-00000000000b'::uuid, false) $$,
  'each owner''s setting is as they left it'
);

-- The counts. Owner A, in the last 7 days: source 3c:..:01 twice 40 minutes
-- apart (2 incidents, the first of two events); one IP address without a
-- hardware address (1); a known device (0); an info-only IDS alert (0); two
-- new devices, one of which also scanned (1); the DeKoi offline for 13 hours
-- (1, and its return is information). Older events and owner B's do not count.

insert into public.network_hosts (device_id, mac, ip, trusted) values
  ('00000000-0000-0000-0000-0000000000a1', '3c:22:fb:00:00:7e', '192.168.1.7', true);

insert into public.events (device_id, occurred_at, kind, src_ip, src_mac, detail) values
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '8 days', 'port_scan', '192.168.1.10', '3c:22:fb:00:00:01', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '2 days', 'port_scan', '192.168.1.10', '3c:22:fb:00:00:01', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '2 days' + interval '20 minutes', 'port_scan', '192.168.1.10', '3c:22:fb:00:00:01', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '2 days' + interval '60 minutes', 'connection', '192.168.1.10', '3c:22:fb:00:00:01', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '1 day', 'login_attempt', '192.168.1.50', null, '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '1 day', 'port_scan', '192.168.1.7', '3c:22:fb:00:00:7e', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '1 day', 'ids_alert', '192.168.1.11', '3c:22:fb:00:00:11', '{"severity": 3}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '3 days', 'new_host', '192.168.1.20', '3c:22:fb:00:00:20', '{}'),
  ('00000000-0000-0000-0000-0000000000a2', now() - interval '2 days', 'new_host', '192.168.1.20', '3c:22:fb:00:00:20', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '1 day', 'new_host', '192.168.1.21', '3c:22:fb:00:00:21', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '1 day' + interval '10 minutes', 'port_scan', '192.168.1.21', '3c:22:fb:00:00:21', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '6 days', 'device_offline', null, null, '{}'),
  ('00000000-0000-0000-0000-0000000000a1', now() - interval '6 days' + interval '13 hours', 'device_online', null, null, '{}'),
  ('00000000-0000-0000-0000-0000000000b1', now() - interval '8 days', 'device_offline', null, null, '{}'),
  ('00000000-0000-0000-0000-0000000000b1', now() - interval '6 days', 'device_online', null, null, '{}'),
  ('00000000-0000-0000-0000-0000000000b1', now() - interval '1 day', 'port_scan', '10.0.0.9', '3c:22:fb:00:00:99', '{}'),
  ('00000000-0000-0000-0000-0000000000b1', now() - interval '1 day', 'new_host', '10.0.0.9', '3c:22:fb:00:00:99', '{}');

select is(
  private.incident_count('00000000-0000-0000-0000-00000000000a', now() - interval '7 days'),
  5,
  'incidents are grouped by DeKoi and source within 30 minutes, without known devices or information only'
);

select is(
  private.uptime_percent('00000000-0000-0000-0000-00000000000b', now() - interval '7 days'),
  85,
  'an outage that began before the week counts from the start of the week'
);

select is(
  private.uptime_percent('00000000-0000-0000-0000-00000000000c', now() - interval '7 days'),
  null,
  'without a registered DeKoi there is no uptime'
);

-- A1 was online 155 of 168 hours, A2 all of its 72 hours since it was
-- linked: 227 of 240 hours, 94.6 %, rounded down.
select results_eq(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000a') order by token $$,
  $$ values ('a-phone', 5, 2, 94), ('a-tablet', 5, 2, 94) $$,
  'every phone of the owner gets the counts: incidents, distinct new devices, uptime'
);

select is_empty(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000a') $$,
  'a second request in the same week sends nothing'
);

update private.weekly_summaries set sent_at = now() - interval '6 days'
where user_id = '00000000-0000-0000-0000-00000000000a';

select results_eq(
  $$ select count(*)::integer from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000a') $$,
  'values (2)',
  'the next week''s request sends again, even if the job runs a little early'
);

select is_empty(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000b') $$,
  'an owner who turned the summary off gets none'
);

select is_empty(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000c') $$,
  'an owner without a registered DeKoi gets none'
);

select is_empty(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-0000000000ff') $$,
  'an unknown account gets none'
);

select is_empty(
  'select * from public.weekly_summary_v1(null)',
  'no account gets none'
);

select results_eq(
  'select user_id from private.weekly_summaries',
  $$ values ('00000000-0000-0000-0000-00000000000a'::uuid) $$,
  'only a summary that was due is recorded'
);

update public.owner_settings set weekly_summary = true where user_id = '00000000-0000-0000-0000-00000000000b';

select results_eq(
  $$ select * from public.weekly_summary_v1('00000000-0000-0000-0000-00000000000b') $$,
  $$ values ('b-phone', 1, 1, 85) $$,
  'another owner''s summary counts only their own DeKoi'
);

set local role service_role;
select lives_ok(
  $$ select public.weekly_summary_v1('00000000-0000-0000-0000-0000000000ff') $$,
  'the notify function may ask for a summary'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select throws_ok(
  $$ select public.weekly_summary_v1('00000000-0000-0000-0000-00000000000a') $$,
  '42501', null,
  'a signed-in user may not ask for a summary'
);

set local role anon;
select throws_ok(
  $$ select public.weekly_summary_v1('00000000-0000-0000-0000-00000000000a') $$,
  '42501', null,
  'an anonymous caller may not ask for a summary'
);

reset role;

-- The weekly job.

select is(private.post_weekly_summaries(), 0, 'without the webhook secrets the job posts nothing');

delete from vault.secrets where name in ('notify_url', 'notify_secret');
select vault.create_secret('http://notify.test.invalid/functions/v1/notify', 'notify_url');
select vault.create_secret('test-shared-secret', 'notify_secret');

select is(private.post_weekly_summaries(), 2, 'the job posts once per owner with a registered DeKoi');

select results_eq(
  $$
    select q.headers ->> 'X-Notify-Secret', convert_from(q.body, 'UTF8')::jsonb
    from net.http_request_queue as q
    where q.url = 'http://notify.test.invalid/functions/v1/notify'
    order by 2
  $$,
  $$
    values
      ('test-shared-secret', '{"weekly_summary_for": "00000000-0000-0000-0000-00000000000a"}'::jsonb),
      ('test-shared-secret', '{"weekly_summary_for": "00000000-0000-0000-0000-00000000000b"}'::jsonb)
  $$,
  'each request carries the owner id and the shared secret, nothing else'
);

-- The setting and the record of sent summaries go with the account.

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
select public.delete_account_v1();
reset role;

select is_empty(
  $$
    select s.user_id from public.owner_settings as s where s.user_id = '00000000-0000-0000-0000-00000000000a'
    union all
    select w.user_id from private.weekly_summaries as w where w.user_id = '00000000-0000-0000-0000-00000000000a'
  $$,
  'deleting the account deletes the setting and the summary record'
);

select * from finish();
rollback;

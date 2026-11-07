-- notification_targets_v2: v1's decision plus severity and the source's kind.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid');

insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device');

insert into private.push_tokens (token, user_id) values
  ('a-phone', '00000000-0000-0000-0000-00000000000a');

insert into public.network_hosts (device_id, mac, ip, kind) values
  ('00000000-0000-0000-0000-0000000000a1', 'aa:bb:cc:00:00:01', '192.168.1.20', 'tv');

insert into public.events (device_id, agent_event_id, occurred_at, kind, src_ip, src_mac) values
  ('00000000-0000-0000-0000-0000000000a1', 1, now(), 'port_scan', '192.168.1.20', 'aa:bb:cc:00:00:01'),
  ('00000000-0000-0000-0000-0000000000a1', 2, now(), 'login_attempt', '192.168.1.60', null),
  ('00000000-0000-0000-0000-0000000000a1', 3, now(), 'device_offline', null, null),
  ('00000000-0000-0000-0000-0000000000a1', 4, now(), 'login_attempt', '192.168.1.60', null);

create function pg_temp.targets(agent_event_id bigint)
  returns setof record
  language sql
as $$
  select t.token, t.template, t.severity, t.host_kind
  from public.notification_targets_v2(
    (select e.id from public.events as e where e.agent_event_id = targets.agent_event_id)
  ) as t
$$;

select results_eq(
  'select * from pg_temp.targets(1) as t (token text, template text, severity text, host_kind text)',
  $$ values ('a-phone', 'port_scan', 'warning', 'tv') $$,
  'a known source names its kind, with the event severity'
);

select results_eq(
  'select * from pg_temp.targets(2) as t (token text, template text, severity text, host_kind text)',
  $$ values ('a-phone', 'login_attempt', 'critical', null::text) $$,
  'a source that is not in the inventory has no kind'
);

select results_eq(
  'select * from pg_temp.targets(3) as t (token text, template text, severity text, host_kind text)',
  $$ values ('a-phone', 'device_offline', 'warning', null::text) $$,
  'device_offline is a warning with no source'
);

select is_empty(
  'select * from pg_temp.targets(4) as t (token text, template text, severity text, host_kind text)',
  'v1 still coalesces: the same source within 10 minutes notifies once'
);

select ok(
  has_function_privilege('service_role', 'public.notification_targets_v2(bigint)', 'execute'),
  'the notify function may call it'
);

select ok(
  not has_function_privilege('anon', 'public.notification_targets_v2(bigint)', 'execute'),
  'anonymous callers may not'
);

select ok(
  not has_function_privilege('authenticated', 'public.notification_targets_v2(bigint)', 'execute'),
  'signed-in users may not'
);

select * from finish();
rollback;

-- Offline detection and retention.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid');

insert into public.devices (id, owner_id, name, online, last_seen_at) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'Silent', true, now() - interval '31 minutes'),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000000a', 'Alive', true, now() - interval '5 minutes');

select is(private.mark_offline_devices(), 1, 'a device silent for 30 minutes goes offline');
select is(private.mark_offline_devices(), 0, 'an offline device is reported only once');

select results_eq(
  $$ select d.name from public.events as e join public.devices as d on d.id = e.device_id where e.kind = 'device_offline' $$,
  $$ values ('Silent') $$,
  'one device_offline event for the silent device'
);

insert into public.events (device_id, occurred_at, kind) values
  ('00000000-0000-0000-0000-0000000000a2', now() - interval '91 days', 'device_online');

insert into public.network_hosts (device_id, mac, ip, last_seen_at) values
  ('00000000-0000-0000-0000-0000000000a2', '3c:22:fb:00:00:01', '192.168.1.10', now() - interval '91 days'),
  ('00000000-0000-0000-0000-0000000000a2', '3c:22:fb:00:00:02', '192.168.1.11', now());

insert into public.networks (device_id, cidr, source, last_seen_at) values
  ('00000000-0000-0000-0000-0000000000a2', '10.0.0.0/24', 'observed', now() - interval '91 days'),
  ('00000000-0000-0000-0000-0000000000a2', '192.168.1.0/24', 'interface', now());

select private.apply_retention();

select is(
  (select count(*)::integer from public.events where occurred_at < now() - interval '90 days'),
  0,
  'events older than 90 days are removed'
);

select results_eq(
  'select mac::text from public.network_hosts',
  $$ values ('3c:22:fb:00:00:02') $$,
  'hosts not seen for 90 days are removed, recent ones stay'
);

select results_eq(
  'select cidr::text from public.networks',
  $$ values ('192.168.1.0/24') $$,
  'networks not seen for 90 days are removed, recent ones stay'
);

select results_eq(
  'select jobname::text, schedule::text from cron.job order by jobname',
  $$
    values
      ('dekoi-mark-offline', '*/5 * * * *'),
      ('dekoi-pending-setups', '37 3 * * *'),
      ('dekoi-rate-counters', '*/10 * * * *'),
      ('dekoi-retention', '17 3 * * *')
  $$,
  'every job is scheduled'
);

select * from finish();
rollback;

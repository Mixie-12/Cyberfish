-- Device identification, networks and event severity (ADR 0010). Abuse cases
-- 1, 2, 4 and 5 for the new columns, table and function.
begin;
create extension if not exists pgtap with schema extensions;
select plan(38);

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

-- One host with every identification field, for the malformed variants below.
create function pg_temp.report(host jsonb, networks jsonb default '[]')
  returns integer
  language sql
as $$
  select public.device_hosts_v2(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
    jsonb_build_array(
      '{"mac": "3c:22:fb:00:00:09", "ip": "192.168.1.90", "kind": "printer"}'::jsonb || host
    ),
    networks
  )
$$;

grant execute on function pg_temp.report(jsonb, jsonb) to anon;

set local role anon;

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('b', 64), '[]', '[]') $$,
  '28000', 'device authentication failed',
  'v2: another device''s secret is refused'
);

select is(
  public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
    {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.50", "vendor": "Apple, Inc.",
     "hostname": "Kari's iPhone", "model": "iPhone15,2", "kind": "phone", "os": "ios",
     "services": ["airplay", "companion-link"]},
    {"mac": "02:00:00:00:00:01", "ip": "192.168.1.51"}
  ]$json$::jsonb, $json$[
    {"cidr": "192.168.1.0/24", "source": "interface", "gateway": "192.168.1.1"},
    {"cidr": "2001:db8:1::/64", "source": "router_advertisement", "gateway": "fe80::1"}
  ]$json$::jsonb),
  4,
  'v2 stores hosts with their identification and networks'
);

select is(
  public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
    {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.60", "kind": "unknown", "services": []},
    {"mac": "02:00:00:00:00:01", "ip": "192.168.1.51", "hostname": "LGwebOSTV", "kind": "tv", "os": "webos"}
  ]$json$::jsonb, $json$[
    {"cidr": "192.168.1.0/24", "source": "interface", "gateway": "192.168.1.254"}
  ]$json$::jsonb),
  3,
  'v2 updates known hosts and networks in place'
);

select is(
  public.device_hosts_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
    '[{"mac": "3c:22:fb:00:00:02", "ip": "192.168.1.52"}]'),
  1,
  'v1 keeps working next to v2'
);

select is(
  public.device_hosts_v2('00000000-0000-0000-0000-0000000000b1', repeat('b', 64),
    '[{"mac": "3c:22:fb:00:00:0b", "ip": "10.0.0.20", "kind": "computer"}]',
    '[{"cidr": "10.0.0.0/24", "source": "interface"}]'),
  2,
  'another device reports its own network'
);

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '{}', '[]') $$,
  '22023', 'invalid argument',
  'v2: hosts that are not an array are refused'
);

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '[]', 'null') $$,
  '22023', 'invalid argument',
  'v2: networks that are not an array are refused'
);

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       (select jsonb_agg(jsonb_build_object('mac', '3c:22:fb:00:00:01', 'ip', '192.168.1.50'))
        from generate_series(1, 1025)), '[]') $$,
  '22023', 'invalid argument',
  'v2: more than 1024 hosts are refused'
);

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), '[]',
       (select jsonb_agg(jsonb_build_object('cidr', format('10.0.%s.0/24', n), 'source', 'observed'))
        from generate_series(1, 65) as n)) $$,
  '22023', 'invalid argument',
  'v2: more than 64 networks are refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('hostname', repeat('x', 65))) $$,
  '23514', null,
  'v2: an oversized host name is refused'
);

select throws_ok(
  $$ select public.device_hosts_v2('00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
       '[{"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.50"}, {"mac": "3C-22-FB-00-00-01", "ip": "192.168.1.51"}]',
       '[]') $$,
  '22023', 'invalid argument',
  'v2: a host reported twice is refused'
);

select throws_ok(
  $$ select pg_temp.report('{}', '[{"cidr": "10.1.0.0/16", "source": "observed"}, {"cidr": "10.1.0.0/16", "source": "interface"}]') $$,
  '22023', 'invalid argument',
  'v2: a network reported twice is refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('model', 'Laser' || chr(27) || '[31m')) $$,
  '23514', null,
  'v2: control characters in a model are refused'
);

select throws_ok(
  $$ select pg_temp.report('{"hostname": ""}') $$,
  '23514', null,
  'v2: an empty host name is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"kind": "toaster"}') $$,
  '23514', null,
  'v2: a kind outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"os": "Windows 11"}') $$,
  '23514', null,
  'v2: an os outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('services',
       (select jsonb_agg('s' || n) from generate_series(1, 17) as n))) $$,
  '23514', null,
  'v2: more than 16 services are refused'
);

select throws_ok(
  $$ select pg_temp.report('{"services": ["IPP_Everywhere"]}') $$,
  '23514', null,
  'v2: a service label outside [a-z0-9-] is refused'
);

select throws_ok(
  $$ select pg_temp.report(jsonb_build_object('services', jsonb_build_array(repeat('a', 33)))) $$,
  '23514', null,
  'v2: a service label over 32 characters is refused'
);

select throws_ok(
  $$ select pg_temp.report('{"services": ["ipp", null]}') $$,
  '23502', null,
  'v2: a null service label is refused'
);

select throws_ok(
  $$ select pg_temp.report('{}', '[{"cidr": "10.1.0.0/16", "source": "guess"}]') $$,
  '23514', null,
  'v2: a network source outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report('{}', '[{"cidr": "10.1.2.3/16", "source": "observed"}]') $$,
  '22P02', null,
  'v2: a network with host bits set is refused'
);

select throws_ok(
  $$ select count(*) from public.networks $$,
  '42501', null,
  'anon cannot read networks'
);

reset role;

select results_eq(
  $$ select mac::text, host(ip), vendor, hostname, model, kind, os, services::text[]
     from public.network_hosts
     where device_id = '00000000-0000-0000-0000-0000000000a1'
     order by mac $$,
  $$ values
       ('02:00:00:00:00:01', '192.168.1.51', null::text, 'LGwebOSTV', null::text, 'tv', 'webos', '{}'::text[]),
       ('3c:22:fb:00:00:01', '192.168.1.60', 'Apple, Inc.', 'Kari''s iPhone', 'iPhone15,2', 'phone', 'ios',
        '{airplay,companion-link}'::text[]),
       ('3c:22:fb:00:00:02', '192.168.1.52', null, null, null, 'unknown', null, '{}') $$,
  'a report without identification keeps what is known; new identification replaces it'
);

select is(
  (select count(*)::integer from public.network_hosts where mac = '3c:22:fb:00:00:09'),
  0,
  'no malformed report wrote anything'
);

insert into public.events (device_id, occurred_at, kind, detail) values
  ('00000000-0000-0000-0000-0000000000a1', now(), 'login_attempt', '{}'),
  ('00000000-0000-0000-0000-0000000000b1', now(), 'login_attempt', '{}');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select results_eq(
  $$ select hostname, kind, os, services::text[] from public.network_hosts
     where mac = '3c:22:fb:00:00:01' $$,
  $$ values ('Kari''s iPhone', 'phone', 'ios', '{airplay,companion-link}'::text[]) $$,
  'an owner reads the identification of their hosts'
);

select results_eq(
  $$ select cidr::text, source, host(gateway) from public.networks order by cidr $$,
  $$ values ('192.168.1.0/24', 'interface', '192.168.1.254'),
            ('2001:db8:1::/64', 'router_advertisement', 'fe80::1') $$,
  'an owner reads only their own device''s networks'
);

select results_eq(
  $$ select severity from public.events $$,
  $$ values ('critical') $$,
  'an owner reads the severity of their own events only'
);

select throws_ok(
  $$ update public.network_hosts set kind = 'tv' $$,
  '42501', null,
  'an owner cannot change the identification reported by the device'
);

select throws_ok(
  $$ insert into public.networks (device_id, cidr, source)
     values ('00000000-0000-0000-0000-0000000000a1', '10.9.0.0/16', 'observed') $$,
  '42501', null,
  'an owner cannot add networks'
);

select throws_ok(
  $$ update public.networks set source = 'observed' $$,
  '42501', null,
  'an owner cannot change networks'
);

select throws_ok(
  $$ delete from public.networks $$,
  '42501', null,
  'an owner cannot delete networks'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', true);

select results_eq(
  $$ select cidr::text from public.networks $$,
  $$ values ('10.0.0.0/24') $$,
  'another owner reads only their own device''s networks'
);

reset role;

insert into public.events (device_id, agent_event_id, occurred_at, kind, detail) values
  ('00000000-0000-0000-0000-0000000000a1', 1, now(), 'login_attempt', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 2, now(), 'connection', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 3, now(), 'port_scan', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 4, now(), 'host_scan', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 5, now(), 'arp_spoof', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 6, now(), 'rogue_dhcp', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 7, now(), 'rogue_router', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 8, now(), 'name_poisoning', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 9, now(), 'device_offline', '{}'),
  ('00000000-0000-0000-0000-0000000000a1', 10, now(), 'device_online', '{}');

select results_eq(
  $$ select kind, severity from public.events where agent_event_id is not null order by agent_event_id $$,
  $$ values
       ('login_attempt', 'critical'), ('connection', 'warning'), ('port_scan', 'warning'),
       ('host_scan', 'warning'), ('arp_spoof', 'critical'), ('rogue_dhcp', 'critical'),
       ('rogue_router', 'warning'), ('name_poisoning', 'critical'), ('device_offline', 'warning'),
       ('device_online', 'info') $$,
  'every event kind has its severity'
);

insert into public.events (device_id, agent_event_id, occurred_at, kind, detail) values
  ('00000000-0000-0000-0000-0000000000a1', 11, now(), 'ids_alert', '{"severity": 1}'),
  ('00000000-0000-0000-0000-0000000000a1', 12, now(), 'ids_alert', '{"severity": 2}'),
  ('00000000-0000-0000-0000-0000000000a1', 13, now(), 'ids_alert', '{"severity": 3}'),
  ('00000000-0000-0000-0000-0000000000a1', 14, now(), 'ids_alert', '{"severity": 4}'),
  ('00000000-0000-0000-0000-0000000000a1', 15, now(), 'ids_alert', '{}');

select results_eq(
  $$ select severity from public.events where agent_event_id between 11 and 15 order by agent_event_id $$,
  $$ values ('critical'), ('warning'), ('info'), ('info'), ('warning') $$,
  'IDS alerts follow the Suricata severity; without one they are a warning'
);

select is_empty(
  $$ select k from unnest(private.device_event_kinds() || array['device_offline', 'device_online']) as k
     where private.event_severity(k, '{}') is null $$,
  'no event kind is without a severity'
);

select throws_ok(
  $$ insert into public.events (device_id, occurred_at, kind, detail)
     values ('00000000-0000-0000-0000-0000000000a1', now(), 'ids_alert', '{"severity": "1"}') $$,
  '23514', null,
  'an IDS severity given as text is still refused'
);

select throws_ok(
  $$ update public.events set severity = 'info' $$,
  '428C9', null,
  'severity cannot be written, only derived'
);

select * from finish();
rollback;

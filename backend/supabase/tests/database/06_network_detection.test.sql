-- Network detection event kinds and their details (ADR 0003).
begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid');
insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000000a', 'One');
insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000d1', private.sha256_text('C' || repeat('0', 24) || '1'),
   private.sha256_text(repeat('a', 64)));

set local role anon;

select is(
  public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), $json$[
    {"agent_event_id": 1, "occurred_at": "now", "kind": "host_scan", "src_ip": "192.168.1.66",
     "src_mac": "3c:22:fb:00:00:66", "detail": {"targets": 254}},
    {"agent_event_id": 2, "occurred_at": "now", "kind": "arp_spoof", "src_mac": "3c:22:fb:00:00:66",
     "detail": {"claimed_ip": "192.168.1.1", "previous_mac": "aa:bb:cc:00:00:01"}},
    {"agent_event_id": 3, "occurred_at": "now", "kind": "rogue_dhcp", "src_ip": "192.168.1.66",
     "detail": {"server_ip": "192.168.1.66", "protocol": "dhcp"}},
    {"agent_event_id": 4, "occurred_at": "now", "kind": "rogue_router", "src_mac": "3c:22:fb:00:00:66",
     "detail": {"protocol": "ra"}},
    {"agent_event_id": 5, "occurred_at": "now", "kind": "name_poisoning", "src_ip": "192.168.1.66",
     "detail": {"protocol": "llmnr"}},
    {"agent_event_id": 6, "occurred_at": "now", "kind": "ids_alert", "src_ip": "192.168.1.66", "dst_port": 445,
     "detail": {"signature": "ET SCAN Suspicious inbound to mySQL port 3306", "category": "Potentially Bad Traffic",
                "severity": 2, "signature_id": 2010937}}
  ]$json$::jsonb),
  6,
  'every network detection kind is accepted with its details'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 7, "occurred_at": "now", "kind": "device_online"}]') $$,
  '22023', 'invalid argument',
  'devices still cannot report backend-only kinds'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 8, "occurred_at": "now", "kind": "host_scan", "detail": {"targets": "254"}}]') $$,
  '23514', null,
  'a numeric field given as text is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 9, "occurred_at": "now", "kind": "host_scan", "detail": {"targets": -1}}]') $$,
  '23514', null,
  'negative numbers are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 10, "occurred_at": "now", "kind": "ids_alert", "detail": {"severity": 2.5}}]') $$,
  '23514', null,
  'fractions are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 11, "occurred_at": "now", "kind": "ids_alert", "detail": {"signature": 5}}]') $$,
  '23514', null,
  'a text field given as a number is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 12, "occurred_at": "now", "kind": "ids_alert", "detail": {"payload": "GET /"}}]') $$,
  '23514', null,
  'payload excerpts are not an accepted field'
);

reset role;

select lives_ok(
  $$ insert into public.events (device_id, occurred_at, kind)
     values ('00000000-0000-0000-0000-0000000000d1', now(), 'device_offline') $$,
  'backend-generated kinds are still valid in the table'
);

select results_eq(
  $$ select kind from public.events where agent_event_id is not null order by agent_event_id $$,
  $$ values ('host_scan'), ('arp_spoof'), ('rogue_dhcp'), ('rogue_router'), ('name_poisoning'), ('ids_alert') $$,
  'the stored kinds are exactly the reported ones'
);

select * from finish();
rollback;

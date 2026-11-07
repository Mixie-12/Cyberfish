-- Honeytoken user names (ADR 0023): a sign-in with a user name from the
-- decoy's bait files carries "bait": true, only as a boolean, and stays
-- critical. Every detail key accepted before is still accepted.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid');
insert into public.devices (id, owner_id, name) values
  ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000000a', 'One');
insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000d1', private.sha256_text('C' || repeat('0', 24) || '1'),
   private.sha256_text(repeat('a', 64)));

set local role anon;

select is(
  public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64), $json$[
    {"agent_event_id": 1, "occurred_at": "now", "kind": "login_attempt", "service": "smb",
     "src_ip": "192.168.1.66", "dst_port": 445,
     "detail": {"username": "familie.admin", "bait": true}},
    {"agent_event_id": 2, "occurred_at": "now", "kind": "login_attempt", "service": "http",
     "src_ip": "192.168.1.66", "dst_port": 80,
     "detail": {"username": "nas-backup", "path": "/index.html", "bait": true}}
  ]$json$::jsonb),
  2,
  'sign-ins with a bait user name are accepted with the flag'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 3, "occurred_at": "now", "kind": "login_attempt",
          "detail": {"username": "admin", "bait": "true"}}]') $$,
  '23514', null,
  'bait given as a string is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 4, "occurred_at": "now", "kind": "login_attempt",
          "detail": {"username": "admin", "bait": 1}}]') $$,
  '23514', null,
  'bait given as a number is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 5, "occurred_at": "now", "kind": "login_attempt",
          "detail": {"username": "familie.admin", "bait": true, "password": "Sommer2019!"}}]') $$,
  '23514', null,
  'a typed password is still not an accepted field'
);

reset role;

select results_eq(
  $$ select severity, detail ->> 'bait' from public.events order by agent_event_id $$,
  $$ values ('critical', 'true'), ('critical', 'true') $$,
  'a bait sign-in is stored with its flag and stays critical'
);

select ok(
  private.valid_detail($json${
    "username": "u", "path": "/", "user_agent": "a", "client_version": "v", "command": "c",
    "claimed_ip": "192.168.1.1", "previous_mac": "02:00:00:00:00:01", "server_ip": "192.168.1.2",
    "protocol": "llmnr", "signature": "s", "category": "c", "share": "Public", "file": "f",
    "targets": 12, "severity": 1, "signature_id": 2000001, "ports": [22, 80],
    "security": "wpa2", "home_security": "wpa3", "channel": 36, "signal_dbm": -60
  }$json$::jsonb),
  'every detail key accepted before is still accepted'
);

select ok(
  not private.valid_detail('{"bait": null}'),
  'a null bait is refused'
);

select * from finish();
rollback;

-- The decoy file share (ADR 0018): file_access with the share and the file,
-- critical, and the same bounds as every other attacker-influenced detail.
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

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
     "src_ip": "192.168.1.66", "dst_port": 445, "detail": {"username": "admin"}},
    {"agent_event_id": 2, "occurred_at": "now", "kind": "connection", "service": "smb",
     "src_ip": "192.168.1.66", "dst_port": 445, "detail": {"share": "Public"}},
    {"agent_event_id": 3, "occurred_at": "now", "kind": "file_access", "service": "smb",
     "src_ip": "192.168.1.66", "dst_port": 445,
     "detail": {"share": "Public", "file": "scans/passport.pdf"}}
  ]$json$::jsonb),
  3,
  'a sign-in, a share connection and a file opened on the file share are accepted'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       pg_catalog.jsonb_build_array(pg_catalog.jsonb_build_object(
         'agent_event_id', 4, 'occurred_at', 'now', 'kind', 'file_access',
         'detail', pg_catalog.jsonb_build_object('file', repeat('x', 257))))) $$,
  '23514', null,
  'a file name longer than 256 characters is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 5, "occurred_at": "now", "kind": "file_access",
          "detail": {"file": "passport\u001b[31m.pdf"}}]') $$,
  '23514', null,
  'control characters in a file name are refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 6, "occurred_at": "now", "kind": "connection", "detail": {"share": 1}}]') $$,
  '23514', null,
  'a share given as a number is refused'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 7, "occurred_at": "now", "kind": "login_attempt",
          "detail": {"username": "admin", "password": "hunter2"}}]') $$,
  '23514', null,
  'a typed password is not an accepted field'
);

select throws_ok(
  $$ select public.device_events_v1('00000000-0000-0000-0000-0000000000d1', repeat('a', 64),
       '[{"agent_event_id": 8, "occurred_at": "now", "kind": "file_read"}]') $$,
  '22023', 'invalid argument',
  'kinds outside the list are still refused'
);

reset role;

select results_eq(
  $$ select kind, severity from public.events order by agent_event_id $$,
  $$ values ('login_attempt', 'critical'), ('connection', 'warning'), ('file_access', 'critical') $$,
  'opening a file on the decoy is critical'
);

select ok(
  'file_access' = any (private.device_event_kinds()),
  'file_access is a kind devices may report'
);

select * from finish();
rollback;

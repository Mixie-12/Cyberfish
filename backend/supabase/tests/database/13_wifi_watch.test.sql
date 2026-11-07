-- Wi-Fi watch (ADR 0019): the owner-written network name, the device RPC
-- that reads it, and the new event kinds. Abuse cases 1, 2, 4 and 5.
begin;
create extension if not exists pgtap with schema extensions;
select plan(33);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test.invalid'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test.invalid');

insert into public.devices (id, owner_id, name, wifi_name) values
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'A device', null),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'B device', 'B home');

insert into private.device_credentials (device_id, claim_code_hash, secret_hash) values
  ('00000000-0000-0000-0000-0000000000a1', private.sha256_text('C' || repeat('0', 24) || 'A'),
   private.sha256_text(repeat('a', 64))),
  ('00000000-0000-0000-0000-0000000000b1', private.sha256_text('C' || repeat('0', 24) || 'B'),
   private.sha256_text(repeat('b', 64)));

-- One Wi-Fi watch event with the given detail, for the malformed variants below.
create function pg_temp.report(id integer, detail jsonb)
  returns integer
  language sql
as $$
  select public.device_events_v1(
    '00000000-0000-0000-0000-0000000000a1', repeat('a', 64),
    jsonb_build_array(jsonb_build_object(
      'agent_event_id', id, 'occurred_at', now(), 'kind', 'evil_twin',
      'src_mac', '02:00:00:00:00:01', 'detail', detail
    ))
  )
$$;

grant execute on function pg_temp.report(integer, jsonb) to anon;

-- The device's side.
set local role anon;

select throws_ok(
  $$ select public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('c', 64), 'no_name') $$,
  '28000', 'device authentication failed',
  'a wrong secret is refused'
);

select throws_ok(
  $$ select public.device_wifi_v1('00000000-0000-0000-0000-0000000000b1', repeat('a', 64), 'no_name') $$,
  '28000', 'device authentication failed',
  'a device cannot read another device''s Wi-Fi name'
);

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name'),
  '{"name": null, "country": null}'::jsonb,
  'without a name and without a country header both are null'
);

select set_config('request.headers', '{"cf-ipcountry": "NO"}', true);

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name'),
  '{"name": null, "country": "NO"}'::jsonb,
  'the country comes from the platform''s request header'
);

select set_config('request.headers', '{"cf-ipcountry": "XX"}', true);

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name') ->> 'country',
  null,
  'an unknown country (XX) is no country'
);

select set_config('request.headers', '{"cf-ipcountry": "T1"}', true);

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name') ->> 'country',
  null,
  'Tor (T1) is no country'
);

select set_config('request.headers', '{"cf-ipcountry": "NO; reg set US"}', true);

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name') ->> 'country',
  null,
  'anything but two capital letters is no country'
);

select throws_ok(
  $$ select public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'on fire') $$,
  '23514', null,
  'a watch state outside the vocabulary is refused'
);

select lives_ok(
  $$ select public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'learning') $$,
  'a device reports the state of its watch'
);

select is(
  pg_temp.report(1, '{"security": "open", "home_security": "wpa2", "channel": 6, "signal_dbm": -45}')
    + public.device_events_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), $json$[
        {"agent_event_id": 2, "occurred_at": "now", "kind": "new_access_point", "src_mac": "02:00:00:00:00:02",
         "detail": {"security": "wpa3", "home_security": "wpa3", "channel": 149, "signal_dbm": 0}}
      ]$json$::jsonb),
  2,
  'both Wi-Fi watch kinds are accepted with their details'
);

select throws_ok(
  $$ select pg_temp.report(3, '{"security": "wpa4"}') $$,
  '23514', null,
  'a security outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report(4, '{"home_security": "WPA2 Personal"}') $$,
  '23514', null,
  'a learned security outside the vocabulary is refused'
);

select throws_ok(
  $$ select pg_temp.report(5, '{"channel": 0}') $$,
  '23514', null,
  'channel 0 is refused'
);

select throws_ok(
  $$ select pg_temp.report(6, '{"channel": 1000}') $$,
  '23514', null,
  'a channel over three digits is refused'
);

select throws_ok(
  $$ select pg_temp.report(7, '{"channel": "6"}') $$,
  '23514', null,
  'a channel given as text is refused'
);

select throws_ok(
  $$ select pg_temp.report(8, '{"signal_dbm": 5}') $$,
  '23514', null,
  'a positive signal is refused'
);

select throws_ok(
  $$ select pg_temp.report(9, '{"signal_dbm": -45.5}') $$,
  '23514', null,
  'a fractional signal is refused'
);

select throws_ok(
  $$ select pg_temp.report(10, '{"ssid": "Home"}') $$,
  '23514', null,
  'the network name is not an accepted field'
);

select throws_ok(
  $$ update public.devices set wifi_name = 'Home' $$,
  '42501', null,
  'anon cannot write a Wi-Fi name'
);

reset role;

select results_eq(
  $$ select kind, severity from public.events order by agent_event_id $$,
  $$ values ('evil_twin', 'critical'), ('new_access_point', 'warning') $$,
  'an evil twin is critical and a new access point a warning'
);

select is(
  (select wifi_watch from public.devices where id = '00000000-0000-0000-0000-0000000000a1'),
  'learning',
  'the reported state is stored on the device row'
);

-- The owner's side.
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);

select lives_ok(
  $$ update public.devices set wifi_name = 'Kari''s Wi-Fi 5G' where id = '00000000-0000-0000-0000-0000000000a1' $$,
  'an owner sets the Wi-Fi name of their device'
);

select lives_ok(
  $$ update public.devices set wifi_name = repeat('æ', 16) where id = '00000000-0000-0000-0000-0000000000a1' $$,
  'a name of 32 bytes is accepted'
);

select throws_ok(
  $$ update public.devices set wifi_name = repeat('æ', 17) where id = '00000000-0000-0000-0000-0000000000a1' $$,
  '23514', null,
  'a name over 32 bytes is refused, even with 17 characters'
);

select throws_ok(
  $$ update public.devices set wifi_name = 'Home' || chr(10) || 'Net' where id = '00000000-0000-0000-0000-0000000000a1' $$,
  '23514', null,
  'control characters in a name are refused'
);

select throws_ok(
  $$ update public.devices set wifi_name = '' where id = '00000000-0000-0000-0000-0000000000a1' $$,
  '23514', null,
  'an empty name is refused; no name is null'
);

select throws_ok(
  $$ update public.devices set wifi_watch = 'watching' $$,
  '42501', null,
  'an owner cannot write the state the device reports'
);

select lives_ok(
  $$ update public.devices set wifi_name = 'Taken over' where id = '00000000-0000-0000-0000-0000000000b1' $$,
  'updating another account''s device runs but matches nothing'
);

select results_eq(
  $$ select wifi_name, wifi_watch from public.devices $$,
  $$ values (repeat('æ', 16), 'learning') $$,
  'an owner reads the name and state of their own device only'
);

select lives_ok(
  $$ update public.devices set wifi_name = null where id = '00000000-0000-0000-0000-0000000000a1' $$,
  'an owner clears the Wi-Fi name'
);

reset role;

select is(
  (select wifi_name from public.devices where id = '00000000-0000-0000-0000-0000000000b1'),
  'B home',
  'another account''s Wi-Fi name is unchanged'
);

select set_config('request.headers', '{"cf-ipcountry": "SE"}', true);
set local role anon;

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000b1', repeat('b', 64), 'watching'),
  '{"name": "B home", "country": "SE"}'::jsonb,
  'a device reads the name its owner set'
);

reset role;

select is(
  public.device_wifi_v1('00000000-0000-0000-0000-0000000000a1', repeat('a', 64), 'no_name') ->> 'name',
  null,
  'a cleared name reaches the device as null'
);

select * from finish();
rollback;

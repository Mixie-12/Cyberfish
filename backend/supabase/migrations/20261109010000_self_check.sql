-- Whether the platform's client headers reach the database. The per-address
-- request limits, a device's public address and country, and the Wi-Fi
-- watch's radio depend on cf-connecting-ip and cf-ipcountry (ADR 0016,
-- 0019); off the platform, or if Supabase stops sending them, they quietly
-- stop working. The monitor workflow calls this daily through the public
-- API and fails when either is false.
--
-- Booleans only, never the address or the country. Security definer
-- because private.client_ip() is the one place that reads the address and
-- anon cannot reach the private schema. Stable, so PostgREST runs it in a
-- read-only transaction. Like every read, the request limits do not count
-- it (ADR 0016; a POST to a volatile function would count, a GET would
-- not); it reads no table, and the statement timeout bounds it.
create function public.self_check_v1()
  returns jsonb
  language sql
  stable
  security definer
  set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'client_address', private.client_ip() is not null,
    'country', coalesce(
      (nullif(pg_catalog.current_setting('request.headers', true), '')::jsonb ->> 'cf-ipcountry') ~ '^[A-Z]{2}$',
      false
    )
  )
$$;

revoke all on function public.self_check_v1() from public, anon, authenticated, service_role;
grant execute on function public.self_check_v1() to anon;

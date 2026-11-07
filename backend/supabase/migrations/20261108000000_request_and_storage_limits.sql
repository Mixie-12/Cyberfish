-- Request and storage limits (ADR 0016). Without a custom domain there is no
-- firewall in front of the project, so the database bounds what one address,
-- one account or one device can make it do. Every refusal is HTTP 429, which
-- the agent retries with backoff, keeping its data.

-- Counts in fixed windows. Unlogged: a crash only resets the limits, and
-- counting costs no WAL. A count is part of its request's transaction, so a
-- failed request leaves no count behind.
create unlogged table private.rate_counters (
  bucket text not null,
  window_end timestamptz not null,
  hits integer not null,
  primary key (bucket, window_end)
);

alter table private.rate_counters enable row level security;

-- Adds hits to the bucket's current window and returns the window's total.
-- The row stays locked until the transaction ends, so every caller takes
-- buckets in the same order to rule out deadlocks.
create function private.count_hits(bucket text, window_length interval, hits integer)
  returns integer
  language sql
  volatile
  set search_path = ''
as $$
  insert into private.rate_counters as c (bucket, window_end, hits)
  values (
    count_hits.bucket,
    pg_catalog.date_bin(count_hits.window_length, now(), timestamptz '2000-01-01 00:00:00+00')
      + count_hits.window_length,
    count_hits.hits
  )
  on conflict on constraint rate_counters_pkey do update
  set hits = c.hits + excluded.hits
  returning c.hits
$$;

-- The caller's address, from CF-Connecting-IP, which the Supabase platform
-- sets on every request (docs: guides/api/query-plans). X-Forwarded-For
-- begins with whatever the caller sent. Null off the platform and outside
-- a Data API request.
create function private.client_ip()
  returns inet
  language sql
  stable
  set search_path = ''
as $$
  select case when pg_catalog.pg_input_is_valid(h.ip, 'inet') then h.ip::inet end
  from (
    select nullif(pg_catalog.current_setting('request.headers', true), '')::jsonb
      ->> 'cf-connecting-ip' as ip
  ) as h
$$;

-- Every request limit. per: the client address, an IPv6 address counted by
-- its /64 because one customer holds a whole /64; or the signed-in user.
-- paths: the requests a limit counts, null for every write. The per-address
-- limits leave room for many DeKois and phones behind one shared address;
-- an agent makes about two writes a minute.
create function private.request_limits()
  returns table (rule text, per text, paths text[], max_hits integer, window_length interval)
  language sql
  immutable
  set search_path = ''
as $$
  values
    ('write', 'ip', null::text[], 600, interval '1 minute'),
    ('write', 'user', null::text[], 120, interval '1 minute'),
    ('register', 'ip', array['/rpc/device_register_v1'], 20, interval '1 hour'),
    ('claim', 'ip', array['/rpc/claim_device_v1'], 30, interval '1 hour'),
    ('claim', 'user', array['/rpc/claim_device_v1'], 20, interval '1 hour')
$$;

-- Only PostgREST's pre-request function lives here: the API roles may run
-- it, and the schema is not exposed, so it is no endpoint.
create schema guard;

-- PostgREST runs this as the request's role before every request. Reads run
-- in a read-only transaction (GET, HEAD, and POST to a stable function) and
-- cannot be counted; the backend's own calls are not limited. Buckets are
-- taken in the order of request_limits().
create function guard.check_request()
  returns void
  language plpgsql
  volatile
  security definer
  set search_path = ''
as $$
declare
  v_claims jsonb := nullif(pg_catalog.current_setting('request.jwt.claims', true), '')::jsonb;
  v_ip inet := private.client_ip();
  v_path text := pg_catalog.current_setting('request.path', true);
  v_limit record;
begin
  if pg_catalog.current_setting('transaction_read_only') = 'on'
    or v_claims ->> 'role' = 'service_role'
  then
    return;
  end if;

  for v_limit in
    select
      l.per || ' ' || l.rule || ' ' || s.subject as bucket,
      l.max_hits,
      l.window_length
    from private.request_limits() with ordinality as l (rule, per, paths, max_hits, window_length, position)
    cross join lateral (
      select case l.per
        when 'ip' then pg_catalog.network(
          pg_catalog.set_masklen(v_ip, case pg_catalog.family(v_ip) when 4 then 32 else 64 end)
        )::text
        when 'user' then v_claims ->> 'sub'
      end as subject
    ) as s
    where s.subject is not null and (l.paths is null or v_path = any (l.paths))
    order by l.position
  loop
    if private.count_hits(v_limit.bucket, v_limit.window_length, 1) > v_limit.max_hits then
      raise exception 'too many requests' using errcode = 'PT429';
    end if;
  end loop;
end;
$$;

grant usage on schema guard to anon, authenticated, service_role;
grant execute on function guard.check_request() to anon, authenticated, service_role;

-- pgrst.* settings on the authenticator role are PostgREST's in-database
-- configuration. The OpenAPI description would list every function a
-- caller may run; the API is documented in docs/architecture.md instead.
-- Aggregates and query plans in GET requests would allow work that no limit
-- here can count; both are off by default and stay off.
alter role authenticator set pgrst.db_pre_request = 'guard.check_request';
alter role authenticator set pgrst.openapi_mode = 'disabled';
alter role authenticator set pgrst.db_aggregates_enabled = 'false';
alter role authenticator set pgrst.db_plan_enabled = 'false';
notify pgrst, 'reload config';

create function private.prune_rate_counters()
  returns void
  language sql
  set search_path = ''
as $$
  delete from private.rate_counters where window_end < now()
$$;

select cron.schedule('dekoi-rate-counters', '*/10 * * * *', 'select private.prune_rate_counters()');

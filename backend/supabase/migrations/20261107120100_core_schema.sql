-- Event details come from the honeypot and are attacker-controlled. Only a
-- small set of keys is accepted, strings are bounded and free of control
-- characters, and the only array is a list of port numbers.
create function private.valid_detail(detail jsonb)
  returns boolean
  language sql
  immutable
  set search_path = ''
as $$
  select pg_catalog.jsonb_typeof(detail) = 'object'
    and pg_catalog.pg_column_size(detail) <= 2048
    and not exists (
      select 1
      from pg_catalog.jsonb_each(detail) as d (key, value)
      where d.key not in ('username', 'path', 'user_agent', 'client_version', 'command', 'ports')
        or case pg_catalog.jsonb_typeof(d.value)
          when 'string' then
            pg_catalog.char_length(d.value #>> '{}') > 256
            or (d.value #>> '{}') ~ '[[:cntrl:]]'
          when 'array' then
            d.key <> 'ports'
            or pg_catalog.jsonb_array_length(d.value) > 64
            or exists (
              select 1
              from pg_catalog.jsonb_array_elements(d.value) as p (port)
              where pg_catalog.jsonb_typeof(p.port) <> 'number'
            )
          else true
        end
    )
$$;

-- A decoy service as named by the agent, for example "ssh" or "http".
create domain private.service_name as text
  check (value ~ '^[a-z0-9_]{1,16}$');

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 40 and name !~ '[[:cntrl:]]'),
  agent_version text check (agent_version ~ '^[0-9A-Za-z.+-]{1,32}$'),
  local_ip inet,
  services private.service_name[] not null default '{}' check (cardinality(services) <= 16),
  online boolean not null default false,
  registered_at timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

create index devices_owner_id_idx on public.devices (owner_id);

-- Pairing and authentication material. Outside the exposed schema, so no API
-- role can select it even by mistake. Hashes only; codes and secrets are
-- 128-bit and 256-bit random values, so a plain SHA-256 is sufficient.
create table private.device_credentials (
  device_id uuid primary key references public.devices (id) on delete cascade,
  claim_code_hash bytea not null unique,
  secret_hash bytea unique
);

create table public.events (
  id bigint generated always as identity primary key,
  device_id uuid not null references public.devices (id) on delete cascade,
  agent_event_id bigint,
  occurred_at timestamptz not null,
  received_at timestamptz not null default now(),
  kind text not null check (
    kind in ('login_attempt', 'connection', 'port_scan', 'device_offline', 'device_online')
  ),
  service private.service_name,
  src_ip inet,
  src_mac macaddr,
  dst_port integer check (dst_port between 0 and 65535),
  count integer not null default 1 check (count between 1 and 1000000),
  detail jsonb not null default '{}' check (private.valid_detail(detail)),
  unique (device_id, agent_event_id)
);

create index events_device_id_occurred_at_idx on public.events (device_id, occurred_at desc);

create table public.network_hosts (
  device_id uuid not null references public.devices (id) on delete cascade,
  mac macaddr not null,
  ip inet not null,
  vendor text check (char_length(vendor) <= 64 and vendor !~ '[[:cntrl:]]'),
  -- The locally administered bit marks randomised addresses (phones, tablets).
  is_private_mac boolean generated always as (
    (get_byte(macaddr_send(mac), 0) & 2) = 2
  ) stored,
  trusted boolean not null default false,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (device_id, mac)
);

-- Written and read only through functions.
create table private.push_tokens (
  token text primary key check (char_length(token) between 1 and 4096),
  user_id uuid not null references auth.users (id) on delete cascade,
  updated_at timestamptz not null default now()
);

create index push_tokens_user_id_idx on private.push_tokens (user_id);

alter table public.devices enable row level security;
alter table public.events enable row level security;
alter table public.network_hosts enable row level security;
alter table private.device_credentials enable row level security;
alter table private.push_tokens enable row level security;

create policy devices_select_own on public.devices
  for select to authenticated
  using (owner_id = (select auth.uid()));

create policy devices_update_own on public.devices
  for update to authenticated
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()));

create policy devices_delete_own on public.devices
  for delete to authenticated
  using (owner_id = (select auth.uid()));

-- The subquery on devices is itself filtered by the devices policies.
create policy events_select_own on public.events
  for select to authenticated
  using (device_id in (select id from public.devices));

create policy network_hosts_select_own on public.network_hosts
  for select to authenticated
  using (device_id in (select id from public.devices));

create policy network_hosts_update_own on public.network_hosts
  for update to authenticated
  using (device_id in (select id from public.devices))
  with check (device_id in (select id from public.devices));

grant select, delete on public.devices to authenticated;
grant update (name) on public.devices to authenticated;
grant select on public.events to authenticated;
grant select on public.network_hosts to authenticated;
grant update (trusted) on public.network_hosts to authenticated;

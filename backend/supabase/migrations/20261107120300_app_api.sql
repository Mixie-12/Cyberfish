-- App API, version 1. Called by signed-in users. Simple owner operations
-- (rename, remove, mark trusted) go through RLS and column grants instead.

-- Links a device to the caller. The same user claiming the same code again
-- gets the same device back; every other failure looks identical, so codes
-- cannot be probed. Codes stay hashed forever and are never reusable.
create function public.claim_device_v1(claim_code text, name text)
  returns uuid
  language plpgsql
  security definer
  set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_code_hash bytea := private.sha256_text(claim_device_v1.claim_code);
  v_device_id uuid;
  v_owner_id uuid;
begin
  if v_user_id is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  if not private.is_claim_code(claim_device_v1.claim_code) then
    raise exception 'invalid claim code' using errcode = '22023';
  end if;

  select c.device_id, d.owner_id into v_device_id, v_owner_id
  from private.device_credentials as c
  join public.devices as d on d.id = c.device_id
  where c.claim_code_hash = v_code_hash;

  if v_device_id is not null then
    if v_owner_id = v_user_id then
      return v_device_id;
    end if;
    raise exception 'invalid claim code' using errcode = '22023';
  end if;

  -- Bounds how many unfinished setups one account can create.
  if (
    select count(*)
    from public.devices as d
    join private.device_credentials as c on c.device_id = d.id
    where d.owner_id = v_user_id and c.secret_hash is null
  ) >= 5 then
    raise exception 'too many devices waiting for setup' using errcode = 'PT429';
  end if;

  insert into public.devices (owner_id, name)
  values (v_user_id, claim_device_v1.name)
  returning id into v_device_id;

  insert into private.device_credentials (device_id, claim_code_hash)
  values (v_device_id, v_code_hash);

  return v_device_id;
end;
$$;

-- A token belongs to whoever signed in on that phone most recently, so it is
-- moved rather than duplicated when accounts switch.
create function public.register_push_token_v1(token text)
  returns void
  language plpgsql
  security definer
  set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  insert into private.push_tokens (token, user_id)
  values (register_push_token_v1.token, auth.uid())
  on conflict on constraint push_tokens_pkey do update
  set user_id = excluded.user_id,
      updated_at = now();
end;
$$;

create function public.unregister_push_token_v1(token text)
  returns void
  language sql
  security definer
  set search_path = ''
as $$
  delete from private.push_tokens as t
  where t.token = unregister_push_token_v1.token and t.user_id = auth.uid()
$$;

-- Removes the account and, through foreign keys, every row that belongs to it.
create function public.delete_account_v1()
  returns void
  language plpgsql
  security definer
  set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  delete from auth.users where id = auth.uid();
end;
$$;

grant execute on function public.claim_device_v1(text, text) to authenticated;
grant execute on function public.register_push_token_v1(text) to authenticated;
grant execute on function public.unregister_push_token_v1(text) to authenticated;
grant execute on function public.delete_account_v1() to authenticated;

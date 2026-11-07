-- The language of a phone's push text (ADR 0025). It is only a key into
-- notify's fixed template table and never appears in a message. Old apps
-- keep calling register_push_token_v1, and the deployed function keeps
-- calling notification_targets_v2 and weekly_summary_v1 until the new one
-- is deployed after this migration; all three stay.

-- The languages notify has templates for (notify.ts, LANGUAGES).
create function private.push_languages()
  returns text[]
  language sql
  immutable
  set search_path = ''
as $$
  select array['en', 'nb']
$$;

-- A constant default: existing rows read English without a table rewrite.
alter table private.push_tokens
  add column language text not null default 'en'
    check (language = any (private.push_languages()));

-- As v1, plus the phone's language; one the templates do not have reads
-- English, so a newer app never fails to register.
create function public.register_push_token_v2(token text, language text)
  returns void
  language plpgsql
  security definer
  set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  insert into private.push_tokens (token, user_id, language)
  values (
    register_push_token_v2.token,
    auth.uid(),
    case
      when register_push_token_v2.language = any (private.push_languages())
        then register_push_token_v2.language
      else 'en'
    end
  )
  on conflict on constraint push_tokens_pkey do update
  set user_id = excluded.user_id,
      language = excluded.language,
      updated_at = now();
end;
$$;

-- Apps that send no language get English.
create or replace function public.register_push_token_v1(token text)
  returns void
  language sql
  security definer
  set search_path = ''
as $$
  select public.register_push_token_v2(register_push_token_v1.token, 'en')
$$;

-- notification_targets_v2 with each phone's language. Who is notified is
-- still decided once, in v1.
create function public.notification_targets_v3(event_id bigint)
  returns table (token text, template text, severity text, host_kind text, language text)
  language sql
  volatile
  security definer
  set search_path = ''
as $$
  select t.token, t.template, t.severity, t.host_kind, p.language
  from public.notification_targets_v2(notification_targets_v3.event_id) as t
  join private.push_tokens as p on p.token = t.token
$$;

-- weekly_summary_v1 with each phone's language; v1 still decides and
-- records the summary.
create function public.weekly_summary_v2(user_id uuid)
  returns table (
    token text, incidents integer, new_devices integer, uptime_percent integer, language text
  )
  language sql
  volatile
  security definer
  set search_path = ''
as $$
  select s.token, s.incidents, s.new_devices, s.uptime_percent, p.language
  from public.weekly_summary_v1(weekly_summary_v2.user_id) as s
  join private.push_tokens as p on p.token = s.token
$$;

revoke all on function public.register_push_token_v2(text, text) from public, anon, authenticated, service_role;
revoke all on function public.notification_targets_v3(bigint) from public, anon, authenticated, service_role;
revoke all on function public.weekly_summary_v2(uuid) from public, anon, authenticated, service_role;
grant execute on function public.register_push_token_v2(text, text) to authenticated;
grant execute on function public.notification_targets_v3(bigint) to service_role;
grant execute on function public.weekly_summary_v2(uuid) to service_role;

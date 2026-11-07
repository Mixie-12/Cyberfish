-- What the notify function needs to word and route a notification (ADR
-- 0011): the targets and template of notification_targets_v1, plus the
-- event's severity and the source device's kind. Both are fixed
-- vocabularies checked by the database (ADR 0010), so they may appear in a
-- notification. Who is notified is still decided once, in v1; v1 stays until
-- no deployed function calls it.
create function public.notification_targets_v2(event_id bigint)
  returns table (token text, template text, severity text, host_kind text)
  language sql
  volatile
  security definer
  set search_path = ''
as $$
  select t.token, t.template, e.severity, h.kind
  from public.notification_targets_v1(notification_targets_v2.event_id) as t
  join public.events as e on e.id = notification_targets_v2.event_id
  left join public.network_hosts as h on h.device_id = e.device_id and h.mac = e.src_mac
$$;

revoke all on function public.notification_targets_v2(bigint) from public, anon, authenticated, service_role;
grant execute on function public.notification_targets_v2(bigint) to service_role;

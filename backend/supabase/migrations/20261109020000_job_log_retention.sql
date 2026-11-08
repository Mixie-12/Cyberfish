-- pg_cron records every run in cron.job_run_details and never deletes one:
-- about 430 runs a day from the jobs scheduled here, 150,000 rows a year in
-- the Free plan's 500 MB. The nightly retention keeps 30 days of them;
-- backend/monitor.sql reads at most the last 8.
create or replace function private.apply_retention()
  returns void
  language sql
  set search_path = ''
as $$
  delete from public.events where occurred_at < now() - interval '90 days';
  delete from public.network_hosts where last_seen_at < now() - interval '90 days';
  delete from public.networks where last_seen_at < now() - interval '90 days';
  delete from public.health_samples where hour < now() - interval '90 days';
  delete from public.port_forwards where last_seen_at < now() - interval '90 days';
  delete from public.speed_tests where tested_at < now() - interval '90 days';
  delete from private.notified_sources where notified_at < now() - interval '1 day';
  delete from cron.job_run_details where end_time < now() - interval '30 days';
$$;

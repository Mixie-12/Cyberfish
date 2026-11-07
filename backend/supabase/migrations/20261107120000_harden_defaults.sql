-- Nothing becomes reachable through the Data API unless a migration grants it
-- explicitly. Supabase grants anon, authenticated and service_role full access
-- to new objects in public, and Postgres grants execute on every new function
-- to PUBLIC. The schema-less form is required for the PUBLIC default: an
-- "in schema" default can add privileges but cannot remove a built-in one.
alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  revoke all on functions from anon, authenticated, service_role;
alter default privileges for role postgres
  revoke execute on functions from public;

-- The GraphQL endpoint is not used.
drop extension if exists pg_graphql;

-- Data and helpers that no API role may ever reach. Only the owner (postgres)
-- has usage, so SECURITY DEFINER functions are the only way in.
create schema private;

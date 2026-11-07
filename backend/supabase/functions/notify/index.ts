// Edge Function "notify": called by the events webhook (migration
// 20261107150000) with the event id and a shared secret.
import { createHandler, parseServiceAccount } from "./notify.ts";

function required(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

Deno.serve(createHandler({
  notifySecret: required("NOTIFY_SECRET"),
  supabaseUrl: required("SUPABASE_URL"),
  serviceRoleKey: required("SUPABASE_SERVICE_ROLE_KEY"),
  serviceAccount: parseServiceAccount(required("FCM_SERVICE_ACCOUNT")),
  // Overridden only by tests, which point it at a local stand-in for FCM.
  fcmBaseUrl: Deno.env.get("FCM_BASE_URL") ?? "https://fcm.googleapis.com",
}));

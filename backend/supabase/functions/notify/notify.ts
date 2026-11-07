// Sends the push notifications for one event, or one owner's weekly summary.
// Whether something notifies, and whom, is decided in SQL
// (notification_targets_v2, weekly_summary_v1); this file only sends.
// Notification text comes from fixed templates chosen by event kind, severity
// and the source device's kind, all three fixed vocabularies checked by the
// database (ADR 0010, 0011). The only other values that reach a message are
// the event id and the summary's three whole numbers, so nothing an attacker
// typed or sent can ever appear on a phone.

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

export interface Config {
  notifySecret: string;
  supabaseUrl: string;
  serviceRoleKey: string;
  serviceAccount: ServiceAccount;
  fcmBaseUrl: string;
}

interface Template {
  title: string;
  body: string;
}

export interface Target {
  token: string;
  template: string;
  severity: string;
  host_kind: string | null;
}

export interface Summary {
  token: string;
  incidents: number;
  new_devices: number;
  uptime_percent: number;
}

// Channel ids must match the channels the app creates (Push.kt); each one
// carries one severity (ADR 0011).
const CHANNELS: Record<string, { channel: string; priority: string; prefix: string }> = {
  critical: { channel: "alerts_urgent", priority: "PRIORITY_HIGH", prefix: "Act now. " },
  warning: { channel: "alerts", priority: "PRIORITY_DEFAULT", prefix: "Check soon. " },
  info: { channel: "updates", priority: "PRIORITY_LOW", prefix: "" },
};

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const ALERT_BODY = "Tap to see what happened and what to do.";

// The source device as the noun of a title. Keys are the host kinds of ADR
// 0010; anything else reads "device". Every noun starts with a consonant
// sound, so titles say "A".
const NOUNS: Record<string, string> = {
  router: "router",
  switch: "network switch",
  access_point: "Wi-Fi access point",
  computer: "computer",
  phone: "phone",
  tablet: "tablet",
  tv: "TV",
  media_player: "streaming box",
  speaker: "speaker",
  game_console: "games console",
  printer: "printer",
  camera: "camera",
  storage: "storage box",
  smart_home: "smart home device",
  wearable: "watch",
  server: "server",
};

// Wording follows the event titles in the app's string resources.
const TEMPLATES: Record<string, (noun: string) => Template> = {
  login_attempt: (n) => ({ title: `A ${n} tried to sign in to your DeKoi`, body: ALERT_BODY }),
  connection: (n) => ({ title: `A ${n} opened one of your decoys`, body: ALERT_BODY }),
  port_scan: (n) => ({ title: `A ${n} searched your network for openings`, body: ALERT_BODY }),
  host_scan: (n) => ({ title: `A ${n} searched your whole network`, body: ALERT_BODY }),
  arp_spoof: (n) => ({ title: `A ${n} is pretending to be another device`, body: ALERT_BODY }),
  rogue_dhcp: (n) => ({ title: `A ${n} is handing out network settings`, body: ALERT_BODY }),
  rogue_router: (n) => ({ title: `A ${n} announces itself as your router`, body: ALERT_BODY }),
  name_poisoning: (n) => ({ title: `A ${n} answers questions it should not`, body: ALERT_BODY }),
  ids_alert: (n) => ({ title: `A ${n} sent suspicious traffic to your DeKoi`, body: ALERT_BODY }),
  new_host: (n) => ({
    title: `A new ${n} joined your network`,
    body: "Tap to see what it looks like. If you know it, mark it as known.",
  }),
  device_offline: () => ({
    title: "Your DeKoi went offline",
    body:
      "It can’t warn you until it’s back. Check that it has power and that its cable is plugged in.",
  }),
};

const OTHER = (): Template => ({ title: "Activity on your DeKoi", body: ALERT_BODY });

export function templateFor(key: string, hostKind: string | null = null): Template {
  const noun = hostKind !== null && Object.hasOwn(NOUNS, hostKind) ? NOUNS[hostKind] : "device";
  return (Object.hasOwn(TEMPLATES, key) ? TEMPLATES[key] : OTHER)(noun);
}

// "no alerts", "1 alert", "3 alerts".
function counted(n: number, noun: string): string {
  if (!Number.isSafeInteger(n) || n < 0) throw new Error("summary counts must be whole numbers");
  if (n === 0) return `no ${noun}s`;
  return `${n} ${noun}${n === 1 ? "" : "s"}`;
}

// Alerts are incidents as the app's Alerts tab groups them (ADR 0013).
export function summaryTemplate(summary: Summary): Template {
  const uptime = summary.uptime_percent;
  if (!Number.isSafeInteger(uptime) || uptime < 0 || uptime > 100) {
    throw new Error("uptime must be a whole percentage");
  }
  const counts = `${counted(summary.incidents, "alert")}, ${
    counted(summary.new_devices, "new device")
  }.`;
  return {
    title: "Your week with DeKoi",
    body: `${counts[0].toUpperCase()}${counts.slice(1)} DeKoi was online ${uptime}% of the time.`,
  };
}

function message(
  token: string,
  severity: string,
  { title, body }: Template,
  tag: string,
  data: Record<string, string>,
) {
  const level = Object.hasOwn(CHANNELS, severity) ? CHANNELS[severity] : CHANNELS.warning;
  return {
    message: {
      token,
      notification: { title, body: level.prefix + body },
      data,
      android: {
        priority: level.channel === "updates" ? "NORMAL" : "HIGH",
        notification: {
          channel_id: level.channel,
          notification_priority: level.priority,
          visibility: "PRIVATE",
          tag,
        },
      },
    },
  };
}

export function fcmMessage(eventId: number, target: Target) {
  // The tag lets the app clear the notification once the alert is opened.
  return message(
    target.token,
    target.severity,
    templateFor(target.template, target.host_kind),
    `event-${eventId}`,
    { event_id: String(eventId) },
  );
}

export function summaryMessage(summary: Summary) {
  // One tag for every summary, so this week's replaces last week's.
  return message(summary.token, "info", summaryTemplate(summary), "weekly-summary", {});
}

export function parseServiceAccount(json: string): ServiceAccount {
  const value: unknown = JSON.parse(json);
  const fields = ["project_id", "client_email", "private_key", "token_uri"] as const;
  if (
    typeof value !== "object" || value === null ||
    !fields.every((f) => typeof (value as Record<string, unknown>)[f] === "string")
  ) {
    throw new Error("FCM service account is missing fields");
  }
  return value as ServiceAccount;
}

const encoder = new TextEncoder();

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(
    /=+$/,
    "",
  );
}

function pemToDer(pem: string): Uint8Array<ArrayBuffer> {
  const base64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

// OAuth 2.0 for service accounts: a self-signed RS256 JWT exchanged for an
// access token (RFC 7523), as Google documents for FCM HTTP v1.
export async function accessToken(
  account: ServiceAccount,
  fetchFn: typeof fetch,
  now = Math.floor(Date.now() / 1000),
): Promise<string> {
  const json = (value: object) => base64url(encoder.encode(JSON.stringify(value)));
  const unsigned = `${json({ alg: "RS256", typ: "JWT" })}.${
    json({
      iss: account.client_email,
      scope: FCM_SCOPE,
      aud: account.token_uri,
      iat: now,
      exp: now + 3600,
    })
  }`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const response = await fetchFn(account.token_uri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64url(new Uint8Array(signature))}`,
    }),
  });
  if (!response.ok) {
    throw new Error(`OAuth token request failed with HTTP ${response.status}`);
  }
  const body: { access_token?: unknown } = await response.json();
  if (typeof body.access_token !== "string") {
    throw new Error("OAuth token response has no access token");
  }
  return body.access_token;
}

// Hashing first makes the comparison independent of where the inputs differ
// and of their lengths.
async function sameSecret(given: string, expected: string): Promise<boolean> {
  const digest = async (s: string) =>
    new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(s)));
  const [a, b] = await Promise.all([digest(given), digest(expected)]);
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// The events webhook posts {"event_id": <id>}; the weekly job posts
// {"weekly_summary_for": "<user id>"} (migration 20261107193100).
type Job = { eventId: number } | { userId: string };

async function readJob(request: Request): Promise<Job | null> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return null;
  }
  const { event_id: id, weekly_summary_for: userId } = (body ?? {}) as {
    event_id?: unknown;
    weekly_summary_for?: unknown;
  };
  if (Number.isSafeInteger(id) && (id as number) > 0) return { eventId: id as number };
  if (typeof userId === "string" && UUID.test(userId)) return { userId };
  return null;
}

function isUnregistered(error: unknown): boolean {
  const details = (error as { error?: { details?: { errorCode?: unknown }[] } })?.error?.details;
  return Array.isArray(details) && details.some((d) => d?.errorCode === "UNREGISTERED");
}

export function createHandler(config: Config, fetchFn: typeof fetch = fetch) {
  const rpc = async (name: string, args: object): Promise<Response> => {
    const response = await fetchFn(`${config.supabaseUrl}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        apikey: config.serviceRoleKey,
        Authorization: `Bearer ${config.serviceRoleKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(args),
    });
    if (!response.ok) throw new Error(`${name} failed with HTTP ${response.status}`);
    return response;
  };
  const rows = async <T>(name: string, args: object): Promise<T[]> =>
    await (await rpc(name, args)).json();

  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const given = request.headers.get("x-notify-secret") ?? "";
    if (!(await sameSecret(given, config.notifySecret))) {
      return new Response(null, { status: 401 });
    }
    const job = await readJob(request);
    if (job === null) return new Response(null, { status: 400 });

    const messages = "eventId" in job
      ? (await rows<Target>("notification_targets_v2", { event_id: job.eventId }))
        .map((target) => fcmMessage(job.eventId, target))
      : (await rows<Summary>("weekly_summary_v1", { user_id: job.userId })).map(summaryMessage);
    if (messages.length === 0) return new Response(null, { status: 204 });

    const token = await accessToken(config.serviceAccount, fetchFn);
    const sendUrl =
      `${config.fcmBaseUrl}/v1/projects/${config.serviceAccount.project_id}/messages:send`;
    let failed = 0;
    for (const outgoing of messages) {
      const response = await fetchFn(sendUrl, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(outgoing),
      });
      if (response.ok) {
        await response.body?.cancel();
        continue;
      }
      // A body that is not JSON is an error other than UNREGISTERED.
      if (isUnregistered(await response.json().catch(() => null))) {
        await (await rpc("forget_push_token_v1", { token: outgoing.message.token })).body?.cancel();
        continue;
      }
      // Status only: tokens and event data stay out of logs.
      console.error(`FCM send failed with HTTP ${response.status}`);
      failed++;
    }
    return new Response(null, { status: failed === 0 ? 204 : 502 });
  };
}

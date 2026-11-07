// Sends the push notifications for one event. Whether an event notifies, and
// whom, is decided in SQL (notification_targets_v1); this file only sends.
// Notification text comes from fixed templates chosen by event kind, and the
// only event field that reaches a message is its id, so nothing an attacker
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

interface Target {
  token: string;
  template: string;
}

// Must match the notification channel the app creates.
export const ALERT_CHANNEL = "alerts";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const ALERT_BODY = "Tap to see what happened and what to do.";

// Wording follows the event titles in the app's string resources.
const TEMPLATES: Record<string, Template> = {
  login_attempt: { title: "Someone tried to sign in to your DeKoi", body: ALERT_BODY },
  connection: { title: "A device opened one of your decoys", body: ALERT_BODY },
  port_scan: { title: "A device searched your network for openings", body: ALERT_BODY },
  host_scan: { title: "A device searched your whole network", body: ALERT_BODY },
  arp_spoof: { title: "A device is pretending to be another device", body: ALERT_BODY },
  rogue_dhcp: { title: "A second device is handing out network settings", body: ALERT_BODY },
  rogue_router: { title: "A second device announces itself as your router", body: ALERT_BODY },
  name_poisoning: { title: "A device answers questions it should not", body: ALERT_BODY },
  ids_alert: { title: "Suspicious traffic reached your DeKoi", body: ALERT_BODY },
  device_offline: {
    title: "Your DeKoi went offline",
    body:
      "It can’t warn you until it’s back. Check that it has power and that its cable is plugged in.",
  },
};

const OTHER: Template = { title: "Activity on your DeKoi", body: ALERT_BODY };

export function templateFor(key: string): Template {
  return Object.hasOwn(TEMPLATES, key) ? TEMPLATES[key] : OTHER;
}

export function fcmMessage(token: string, eventId: number, templateKey: string) {
  const { title, body } = templateFor(templateKey);
  return {
    message: {
      token,
      notification: { title, body },
      data: { event_id: String(eventId) },
      android: { priority: "HIGH", notification: { channel_id: ALERT_CHANNEL } },
    },
  };
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

async function readEventId(request: Request): Promise<number | null> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return null;
  }
  const id = (body as { event_id?: unknown } | null)?.event_id;
  return Number.isSafeInteger(id) && (id as number) > 0 ? id as number : null;
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

  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const given = request.headers.get("x-notify-secret") ?? "";
    if (!(await sameSecret(given, config.notifySecret))) {
      return new Response(null, { status: 401 });
    }
    const eventId = await readEventId(request);
    if (eventId === null) return new Response(null, { status: 400 });

    const targets: Target[] = await (await rpc("notification_targets_v1", { event_id: eventId }))
      .json();
    if (targets.length === 0) return new Response(null, { status: 204 });

    const token = await accessToken(config.serviceAccount, fetchFn);
    const sendUrl =
      `${config.fcmBaseUrl}/v1/projects/${config.serviceAccount.project_id}/messages:send`;
    let failed = 0;
    for (const target of targets) {
      const response = await fetchFn(sendUrl, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(fcmMessage(target.token, eventId, target.template)),
      });
      if (response.ok) {
        await response.body?.cancel();
        continue;
      }
      // A body that is not JSON is an error other than UNREGISTERED.
      if (isUnregistered(await response.json().catch(() => null))) {
        await (await rpc("forget_push_token_v1", { token: target.token })).body?.cancel();
        continue;
      }
      // Status only: tokens and event data stay out of logs.
      console.error(`FCM send failed with HTTP ${response.status}`);
      failed++;
    }
    return new Response(null, { status: failed === 0 ? 204 : 502 });
  };
}

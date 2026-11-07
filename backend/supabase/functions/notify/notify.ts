// Sends the push notifications for one event, or one owner's weekly summary.
// Whether something notifies, and whom, is decided in SQL
// (notification_targets_v3, weekly_summary_v2); this file only sends.
// Notification text comes from fixed templates chosen by the phone's
// language, event kind, severity and the source device's kind, all fixed
// vocabularies checked by the database (ADR 0010, 0011, 0024); each is only
// ever a key into the tables below. The only other values that reach a
// message are the event id and the summary's three whole numbers, so
// nothing an attacker typed or sent can ever appear on a phone.

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

export interface Config {
  // The secret the events webhook sends and, while it is being rotated, the
  // one it sent before (docs/runbooks/rotate-keys.md); empty otherwise.
  notifySecret: string;
  previousNotifySecret: string;
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
  language: string;
}

export interface Summary {
  token: string;
  incidents: number;
  new_devices: number;
  uptime_percent: number;
  language: string;
}

// Channel ids must match the channels the app creates (Push.kt); each one
// carries one severity (ADR 0011). The colours are copies of the severity
// colours in clients/shared DesignTokens.kt: while the app is in the
// background, the phone draws the notification from this payload alone
// (docs/design.md, "Notifications"). PushTest checks that they match.
const CHANNELS = {
  critical: { channel: "alerts_urgent", priority: "PRIORITY_HIGH", color: "#ff6b66" },
  warning: { channel: "alerts", priority: "PRIORITY_DEFAULT", color: "#ffb547" },
  info: { channel: "updates", priority: "PRIORITY_LOW", color: "#a9b8d4" },
};

type Severity = keyof typeof CHANNELS;

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
// The deploy generates 64 hex characters; anything much shorter is a mistake.
const MIN_SECRET_LENGTH = 32;

// The event kinds with a template of their own; any other kind gets `other`.
type Kind =
  | "login_attempt"
  | "connection"
  | "port_scan"
  | "host_scan"
  | "arp_spoof"
  | "rogue_dhcp"
  | "rogue_router"
  | "name_poisoning"
  | "ids_alert"
  | "new_host"
  | "dns_hijack"
  | "port_forward"
  | "evil_twin"
  | "new_access_point"
  | "file_access"
  | "device_offline";

// The host kinds of ADR 0010 a title names; any other kind is `device`.
type HostKind =
  | "router"
  | "switch"
  | "access_point"
  | "computer"
  | "phone"
  | "tablet"
  | "tv"
  | "media_player"
  | "speaker"
  | "game_console"
  | "printer"
  | "camera"
  | "storage"
  | "smart_home"
  | "wearable"
  | "server";

// A count in words: for none, for one, and the noun after any other number.
type Counted = { none: string; one: string; many: string };

// Everything a message says, in one language. A source device is a whole
// phrase with its article ("A phone", "Et nettbrett"), so no title puts an
// article or adjective next to a placeholder: Norwegian ones agree with the
// noun's gender. Wording follows the app's string resources (subject_*,
// event_*_title), so an alert reads like the notification that led to it.
interface Language {
  subjects: Record<HostKind, string>;
  device: string;
  prefixes: Record<Severity, string>;
  templates: Record<Kind, (subject: string) => Template>;
  other: Template;
  week: {
    title: string;
    alerts: Counted;
    newDevices: Counted;
    online: (percent: number) => string;
  };
}

const EN_BODY = "Tap to see what happened and what to do.";

const EN: Language = {
  subjects: {
    router: "A router",
    switch: "A network switch",
    access_point: "A Wi-Fi access point",
    computer: "A computer",
    phone: "A phone",
    tablet: "A tablet",
    tv: "A TV",
    media_player: "A streaming box",
    speaker: "A speaker",
    game_console: "A games console",
    printer: "A printer",
    camera: "A camera",
    storage: "A storage box",
    smart_home: "A smart home device",
    wearable: "A watch",
    server: "A server",
  },
  device: "A device",
  prefixes: { critical: "Act now. ", warning: "Check soon. ", info: "" },
  templates: {
    login_attempt: (s) => ({ title: `${s} tried to sign in to your DeKoi`, body: EN_BODY }),
    connection: (s) => ({ title: `${s} opened one of your decoys`, body: EN_BODY }),
    port_scan: (s) => ({ title: `${s} searched your network for openings`, body: EN_BODY }),
    host_scan: (s) => ({ title: `${s} searched your whole network`, body: EN_BODY }),
    arp_spoof: (s) => ({ title: `${s} is pretending to be another device`, body: EN_BODY }),
    rogue_dhcp: (s) => ({ title: `${s} is handing out network settings`, body: EN_BODY }),
    rogue_router: (s) => ({ title: `${s} announces itself as your router`, body: EN_BODY }),
    name_poisoning: (s) => ({ title: `${s} answers questions it should not`, body: EN_BODY }),
    ids_alert: (s) => ({ title: `${s} sent suspicious traffic to your DeKoi`, body: EN_BODY }),
    new_host: (s) => ({
      title: `${s} joined your network for the first time`,
      body: "Tap to see what it looks like. If you know it, mark it as known.",
    }),
    // The source of a dns_hijack event is the resolver, not a device the
    // inventory names.
    dns_hijack: () => ({ title: "Some web addresses lead to the wrong place", body: EN_BODY }),
    port_forward: (s) => ({ title: `${s} can now be reached from the internet`, body: EN_BODY }),
    // The source of a Wi-Fi event is an access point that is not on the
    // network, so these name no device; the network name never appears.
    evil_twin: () => ({ title: "Someone copied your Wi-Fi name", body: EN_BODY }),
    new_access_point: () => ({ title: "A new access point uses your Wi-Fi name", body: EN_BODY }),
    file_access: (s) => ({ title: `${s} opened a file on your decoy file share`, body: EN_BODY }),
    device_offline: () => ({
      title: "Your DeKoi went offline",
      body:
        "It can’t warn you until it’s back. Check that it has power and that its cable is plugged in.",
    }),
  },
  other: { title: "Activity on your DeKoi", body: EN_BODY },
  week: {
    title: "Your week with DeKoi",
    alerts: { none: "no alerts", one: "alert", many: "alerts" },
    newDevices: { none: "no new devices", one: "new device", many: "new devices" },
    online: (percent) => `DeKoi was online ${percent}% of the time.`,
  },
};

const NB_BODY = "Trykk for å se hva som skjedde og hva du bør gjøre.";

const NB: Language = {
  subjects: {
    router: "En ruter",
    switch: "En nettverkssvitsj",
    access_point: "Et Wi-Fi-tilgangspunkt",
    computer: "En datamaskin",
    phone: "En telefon",
    tablet: "Et nettbrett",
    tv: "En TV",
    media_player: "En strømmeboks",
    speaker: "En høyttaler",
    game_console: "En spillkonsoll",
    printer: "En skriver",
    camera: "Et kamera",
    storage: "En lagringsboks",
    smart_home: "En smarthusenhet",
    wearable: "En klokke",
    server: "En server",
  },
  device: "En enhet",
  prefixes: { critical: "Haster. ", warning: "Se på snart. ", info: "" },
  templates: {
    login_attempt: (s) => ({ title: `${s} prøvde å logge på din DeKoi`, body: NB_BODY }),
    connection: (s) => ({ title: `${s} åpnet en av lokkeduene dine`, body: NB_BODY }),
    port_scan: (s) => ({ title: `${s} lette etter åpninger på nettverket ditt`, body: NB_BODY }),
    host_scan: (s) => ({ title: `${s} søkte gjennom hele nettverket ditt`, body: NB_BODY }),
    arp_spoof: (s) => ({ title: `${s} utgir seg for å være en annen enhet`, body: NB_BODY }),
    rogue_dhcp: (s) => ({ title: `${s} deler ut nettverksinnstillinger`, body: NB_BODY }),
    rogue_router: (s) => ({ title: `${s} melder seg som ruteren din`, body: NB_BODY }),
    name_poisoning: (s) => ({
      title: `${s} svarer på spørsmål som er ment for andre`,
      body: NB_BODY,
    }),
    ids_alert: (s) => ({ title: `${s} sendte mistenkelig trafikk til din DeKoi`, body: NB_BODY }),
    // The subject can be neuter (et nettbrett), so the body says "enheten"
    // before it refers back with "den".
    new_host: (s) => ({
      title: `${s} koblet seg til nettverket ditt for første gang`,
      body: "Trykk for å se hvordan enheten ser ut. Hvis du kjenner den, merk den som kjent.",
    }),
    dns_hijack: () => ({ title: "Noen nettadresser fører til feil sted", body: NB_BODY }),
    port_forward: (s) => ({ title: `${s} kan nå nås fra internett`, body: NB_BODY }),
    evil_twin: () => ({ title: "Noen har kopiert navnet på Wi-Fi-nettet ditt", body: NB_BODY }),
    new_access_point: () => ({
      title: "Et nytt tilgangspunkt bruker navnet på Wi-Fi-nettet ditt",
      body: NB_BODY,
    }),
    file_access: (s) => ({ title: `${s} åpnet en lokkefil på din DeKoi`, body: NB_BODY }),
    device_offline: () => ({
      title: "Din DeKoi ble frakoblet",
      body:
        "Den kan ikke varsle deg før den er tilbake. Se etter at den har strøm og at nettverkskabelen står i.",
    }),
  },
  other: { title: "Aktivitet på din DeKoi", body: NB_BODY },
  week: {
    title: "Din uke med DeKoi",
    alerts: { none: "ingen varsler", one: "varsel", many: "varsler" },
    newDevices: { none: "ingen nye enheter", one: "ny enhet", many: "nye enheter" },
    // Norwegian puts a space between a number and the percent sign; a
    // no-break space keeps them on one line.
    online: (percent) => `DeKoi var tilkoblet ${percent} % av tiden.`,
  },
};

// The languages the database accepts (private.push_languages); any other
// reads English.
const LANGUAGES: Record<string, Language> = { en: EN, nb: NB };

function languageOf(code: string): Language {
  return Object.hasOwn(LANGUAGES, code) ? LANGUAGES[code] : EN;
}

export function templateFor(
  key: string,
  hostKind: string | null = null,
  language = "en",
): Template {
  const words = languageOf(language);
  const subject = hostKind !== null && Object.hasOwn(words.subjects, hostKind)
    ? words.subjects[hostKind as HostKind]
    : words.device;
  return Object.hasOwn(words.templates, key) ? words.templates[key as Kind](subject) : words.other;
}

// "no alerts", "1 alert", "3 alerts".
function counted(n: number, { none, one, many }: Counted): string {
  if (!Number.isSafeInteger(n) || n < 0) throw new Error("summary counts must be whole numbers");
  if (n === 0) return none;
  return `${n} ${n === 1 ? one : many}`;
}

// Alerts are incidents as the app's Alerts tab groups them (ADR 0013).
export function summaryTemplate(summary: Summary): Template {
  const uptime = summary.uptime_percent;
  if (!Number.isSafeInteger(uptime) || uptime < 0 || uptime > 100) {
    throw new Error("uptime must be a whole percentage");
  }
  const { week } = languageOf(summary.language);
  const counts = `${counted(summary.incidents, week.alerts)}, ${
    counted(summary.new_devices, week.newDevices)
  }.`;
  return {
    title: week.title,
    body: `${counts[0].toUpperCase()}${counts.slice(1)} ${week.online(uptime)}`,
  };
}

function message(
  token: string,
  severity: string,
  language: string,
  { title, body }: Template,
  tag: string,
  data: Record<string, string>,
) {
  const level: Severity = Object.hasOwn(CHANNELS, severity) ? severity as Severity : "warning";
  const { channel, priority, color } = CHANNELS[level];
  return {
    message: {
      token,
      notification: { title, body: languageOf(language).prefixes[level] + body },
      data,
      android: {
        priority: channel === "updates" ? "NORMAL" : "HIGH",
        notification: {
          channel_id: channel,
          notification_priority: priority,
          color,
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
    target.language,
    templateFor(target.template, target.host_kind, target.language),
    `event-${eventId}`,
    { event_id: String(eventId) },
  );
}

export function summaryMessage(summary: Summary) {
  // One tag for every summary, so this week's replaces last week's.
  return message(
    summary.token,
    "info",
    summary.language,
    summaryTemplate(summary),
    "weekly-summary",
    {},
  );
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
  const secrets = [config.notifySecret, config.previousNotifySecret].filter((s) => s !== "");
  if (config.notifySecret === "" || secrets.some((s) => s.length < MIN_SECRET_LENGTH)) {
    throw new Error(`notify secrets must be at least ${MIN_SECRET_LENGTH} characters`);
  }

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
    // Every secret is compared, so the time taken does not tell which matched.
    const matches = await Promise.all(secrets.map((s) => sameSecret(given, s)));
    if (!matches.includes(true)) return new Response(null, { status: 401 });
    const job = await readJob(request);
    if (job === null) return new Response(null, { status: 400 });

    const messages = "eventId" in job
      ? (await rows<Target>("notification_targets_v3", { event_id: job.eventId }))
        .map((target) => fcmMessage(job.eventId, target))
      : (await rows<Summary>("weekly_summary_v2", { user_id: job.userId })).map(summaryMessage);
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

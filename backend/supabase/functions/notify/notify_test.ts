import { deepStrictEqual, equal, notEqual, ok, throws } from "node:assert/strict";
import {
  accessToken,
  createHandler,
  fcmMessage,
  type ServiceAccount,
  summaryTemplate,
  type Target,
  templateFor,
} from "./notify.ts";

const SECRET = "a".repeat(64);
const PREVIOUS_SECRET = "b".repeat(64);
const OWNER = "00000000-0000-4000-8000-00000000000a";
const SUPABASE = "http://supabase.test";
const FCM = "http://fcm.test";
const TOKEN_URI = "http://oauth.test/token";

async function serviceAccount(): Promise<{ account: ServiceAccount; publicKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    btoa(String.fromCharCode(...der))
  }\n-----END PRIVATE KEY-----\n`;
  return {
    account: {
      project_id: "dekoi-test",
      client_email: "notify@dekoi-test.iam.gserviceaccount.com",
      private_key: pem,
      token_uri: TOKEN_URI,
    },
    publicKey: pair.publicKey,
  };
}

interface Call {
  url: string;
  body: string;
}

// Stands in for PostgREST, Google OAuth and FCM, and records every request.
// Both decision functions answer with `rows`.
function fakeFetch(rows: unknown[], fcmStatus = 200, fcmBody: unknown = {}) {
  const calls: Call[] = [];
  const fetchFn = (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, body: String(init?.body ?? "") });
    if (
      url === `${SUPABASE}/rest/v1/rpc/notification_targets_v2` ||
      url === `${SUPABASE}/rest/v1/rpc/weekly_summary_v1`
    ) {
      return Promise.resolve(Response.json(rows));
    }
    if (url === `${SUPABASE}/rest/v1/rpc/forget_push_token_v1`) {
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    if (url === TOKEN_URI) return Promise.resolve(Response.json({ access_token: "oauth-token" }));
    if (url === `${FCM}/v1/projects/dekoi-test/messages:send`) {
      return Promise.resolve(Response.json(fcmBody, { status: fcmStatus }));
    }
    return Promise.reject(new Error(`unexpected request to ${url}`));
  };
  return { calls, fetchFn: fetchFn as typeof fetch };
}

async function handler(fetchFn: typeof fetch, previousNotifySecret = "") {
  const { account } = await serviceAccount();
  return createHandler(
    {
      notifySecret: SECRET,
      previousNotifySecret,
      supabaseUrl: SUPABASE,
      serviceRoleKey: "service-role-key",
      serviceAccount: account,
      fcmBaseUrl: FCM,
    },
    fetchFn,
  );
}

function target(
  token: string,
  template: string,
  severity = "warning",
  hostKind: string | null = null,
): Target {
  return { token, template, severity, host_kind: hostKind };
}

function webhook(body: unknown, secret = SECRET): Request {
  return new Request("http://notify.test/", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Notify-Secret": secret },
    body: JSON.stringify(body),
  });
}

Deno.test("every event kind has its own template; anything else gets the generic one", () => {
  const kinds = [
    "login_attempt",
    "connection",
    "port_scan",
    "host_scan",
    "arp_spoof",
    "rogue_dhcp",
    "rogue_router",
    "name_poisoning",
    "ids_alert",
    "new_host",
    "dns_hijack",
    "port_forward",
    "evil_twin",
    "new_access_point",
    "device_offline",
  ];
  const generic = templateFor("something_new");
  const titles = new Set(kinds.map((k) => templateFor(k).title));
  equal(titles.size, kinds.length);
  ok(!titles.has(generic.title));
  deepStrictEqual(templateFor("constructor"), generic);
  deepStrictEqual(templateFor("__proto__"), generic);
  equal(templateFor("device_offline").title, "Your DeKoi went offline");
});

Deno.test("titles name the device kind from the fixed list, nothing else", () => {
  equal(templateFor("port_scan", "tv").title, "A TV searched your network for openings");
  equal(templateFor("port_scan", null).title, "A device searched your network for openings");
  equal(templateFor("port_scan", "<b>evil</b>").title, templateFor("port_scan").title);
  equal(templateFor("port_scan", "__proto__").title, templateFor("port_scan").title);
  equal(
    templateFor("port_forward", "game_console").title,
    "A games console can now be reached from the internet",
  );
  equal(templateFor("evil_twin", "router").title, "Someone copied your Wi-Fi name");
});

Deno.test("a new device is named by its kind and goes to the updates channel", () => {
  equal(templateFor("new_host").title, "A new device joined your network");
  equal(templateFor("new_host", "phone").title, "A new phone joined your network");
  equal(templateFor("new_host", "<b>evil</b>").title, "A new device joined your network");
  const { message } = fcmMessage(9, target("t", "new_host", "info", "tv"));
  equal(message.notification.title, "A new TV joined your network");
  equal(
    message.notification.body,
    "Tap to see what it looks like. If you know it, mark it as known.",
  );
  equal(message.android.notification.channel_id, "updates");
  equal(message.android.priority, "NORMAL");
});

Deno.test("severity picks the channel; anything unexpected is an ordinary alert", () => {
  const channel = (severity: string) =>
    fcmMessage(1, target("t", "port_scan", severity)).message.android.notification.channel_id;
  equal(channel("critical"), "alerts_urgent");
  equal(channel("warning"), "alerts");
  equal(channel("info"), "updates");
  equal(channel("constructor"), "alerts");
});

Deno.test("a wrong or missing secret is refused before anything else happens", async () => {
  const { calls, fetchFn } = fakeFetch([target("phone", "login_attempt")]);
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ event_id: 1 }, "wrong"))).status, 401);
  const noHeader = new Request("http://notify.test/", { method: "POST", body: "{}" });
  equal((await handle(noHeader)).status, 401);
  equal(calls.length, 0);
});

Deno.test("during a rotation the previous secret is accepted too, and nothing else", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn, PREVIOUS_SECRET);
  equal((await handle(webhook({ event_id: 1 }, SECRET))).status, 204);
  equal((await handle(webhook({ event_id: 2 }, PREVIOUS_SECRET))).status, 204);
  equal((await handle(webhook({ event_id: 3 }, "c".repeat(64)))).status, 401);
  equal((await handle(webhook({ event_id: 4 }, ""))).status, 401);
  equal(calls.length, 2);
});

Deno.test("without a previous secret an empty header is still refused", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn, "");
  equal((await handle(webhook({ event_id: 1 }, ""))).status, 401);
  equal((await handle(webhook({ event_id: 1 }, PREVIOUS_SECRET))).status, 401);
  equal(calls.length, 0);
});

Deno.test("short secrets stop the function from starting", async () => {
  const { fetchFn } = fakeFetch([]);
  const { account } = await serviceAccount();
  const config = {
    notifySecret: SECRET,
    previousNotifySecret: "",
    supabaseUrl: SUPABASE,
    serviceRoleKey: "service-role-key",
    serviceAccount: account,
    fcmBaseUrl: FCM,
  };
  throws(() => createHandler({ ...config, notifySecret: "" }, fetchFn));
  throws(() => createHandler({ ...config, notifySecret: "short" }, fetchFn));
  throws(() => createHandler({ ...config, previousNotifySecret: "short" }, fetchFn));
});

Deno.test("a body without a positive integer event id or an owner id is rejected", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn);
  const bodies = [
    {},
    { event_id: "1" },
    { event_id: 1.5 },
    { event_id: -1 },
    null,
    { weekly_summary_for: "a" },
    { weekly_summary_for: 7 },
    { weekly_summary_for: `${OWNER}; drop table events` },
  ];
  for (const body of bodies) {
    equal((await handle(webhook(body))).status, 400);
  }
  equal(calls.length, 0);
});

Deno.test("the weekly summary counts in words, from no alerts to many", () => {
  const summary = (incidents: number, newDevices: number, uptime: number) =>
    summaryTemplate({
      token: "t",
      incidents,
      new_devices: newDevices,
      uptime_percent: uptime,
    });
  deepStrictEqual(summary(0, 0, 100), {
    title: "Your week with DeKoi",
    body: "No alerts, no new devices. DeKoi was online 100% of the time.",
  });
  equal(summary(1, 1, 99).body, "1 alert, 1 new device. DeKoi was online 99% of the time.");
  equal(summary(12, 3, 0).body, "12 alerts, 3 new devices. DeKoi was online 0% of the time.");
});

Deno.test("a summary with anything but whole numbers is not sent", () => {
  const bad = [
    { incidents: -1, new_devices: 0, uptime_percent: 100 },
    { incidents: 1.5, new_devices: 0, uptime_percent: 100 },
    { incidents: 0, new_devices: "<b>2</b>" as unknown as number, uptime_percent: 100 },
    { incidents: 0, new_devices: 0, uptime_percent: 101 },
    { incidents: 0, new_devices: 0, uptime_percent: null as unknown as number },
  ];
  for (const counts of bad) {
    throws(() => summaryTemplate({ token: "t", ...counts }));
  }
});

Deno.test("the weekly summary goes to the owner's phones on the updates channel", async () => {
  const { calls, fetchFn } = fakeFetch([
    { token: "phone-1", incidents: 2, new_devices: 1, uptime_percent: 97 },
  ]);
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ weekly_summary_for: OWNER }))).status, 204);

  const ask = calls.find((c) => c.url === `${SUPABASE}/rest/v1/rpc/weekly_summary_v1`);
  deepStrictEqual(JSON.parse(ask?.body ?? "null"), { user_id: OWNER });
  const sends = calls.filter((c) => c.url.startsWith(FCM));
  equal(sends.length, 1);
  deepStrictEqual(JSON.parse(sends[0].body), {
    message: {
      token: "phone-1",
      notification: {
        title: "Your week with DeKoi",
        body: "2 alerts, 1 new device. DeKoi was online 97% of the time.",
      },
      data: {},
      android: {
        priority: "NORMAL",
        notification: {
          channel_id: "updates",
          notification_priority: "PRIORITY_LOW",
          visibility: "PRIVATE",
          tag: "weekly-summary",
        },
      },
    },
  });
});

Deno.test("no summary due means no token request and no message", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ weekly_summary_for: OWNER }))).status, 204);
  deepStrictEqual(calls.map((c) => c.url), [`${SUPABASE}/rest/v1/rpc/weekly_summary_v1`]);
});

Deno.test("no targets means no token request and no message", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ event_id: 7 }))).status, 204);
  deepStrictEqual(calls.map((c) => c.url), [`${SUPABASE}/rest/v1/rpc/notification_targets_v2`]);
  deepStrictEqual(JSON.parse(calls[0].body), { event_id: 7 });
});

Deno.test("only the event id and the fixed template reach the message", async () => {
  const attacker = "<b>root</b>; rm -rf / \u001b[31m";
  const { calls, fetchFn } = fakeFetch([
    target("phone-1", "login_attempt", "critical", "computer"),
  ]);
  const handle = await handler(fetchFn);
  const response = await handle(webhook({
    event_id: 42,
    type: "INSERT",
    record: { id: 42, kind: "login_attempt", service: "ssh", detail: { username: attacker } },
  }));
  equal(response.status, 204);

  const sends = calls.filter((c) => c.url.startsWith(FCM));
  equal(sends.length, 1);
  ok(!sends[0].body.includes("root"));
  ok(!sends[0].body.includes("ssh"));
  deepStrictEqual(JSON.parse(sends[0].body), {
    message: {
      token: "phone-1",
      notification: {
        title: "A computer tried to sign in to your DeKoi",
        body: "Act now. Tap to see what happened and what to do.",
      },
      data: { event_id: "42" },
      android: {
        priority: "HIGH",
        notification: {
          channel_id: "alerts_urgent",
          notification_priority: "PRIORITY_HIGH",
          visibility: "PRIVATE",
          tag: "event-42",
        },
      },
    },
  });
});

Deno.test("a token FCM reports as unregistered is forgotten", async () => {
  const { calls, fetchFn } = fakeFetch(
    [target("gone", "device_offline")],
    404,
    {
      error: {
        code: 404,
        status: "NOT_FOUND",
        details: [{
          "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
          errorCode: "UNREGISTERED",
        }],
      },
    },
  );
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ event_id: 3 }))).status, 204);
  const forget = calls.find((c) => c.url.endsWith("/rpc/forget_push_token_v1"));
  deepStrictEqual(JSON.parse(forget?.body ?? "null"), { token: "gone" });
});

Deno.test("other FCM errors keep the token and report a failure", async () => {
  const { calls, fetchFn } = fakeFetch([target("phone", "port_scan")], 500, {
    error: { code: 500, status: "INTERNAL" },
  });
  const handle = await handler(fetchFn);
  equal((await handle(webhook({ event_id: 4 }))).status, 502);
  ok(!calls.some((c) => c.url.endsWith("/rpc/forget_push_token_v1")));
});

Deno.test("the OAuth assertion is an RS256 JWT signed by the service account", async () => {
  const { account, publicKey } = await serviceAccount();
  let assertion = "";
  const fetchFn = (_input: string | URL | Request, init?: RequestInit) => {
    const form = init?.body as URLSearchParams;
    equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
    assertion = form.get("assertion") ?? "";
    return Promise.resolve(Response.json({ access_token: "oauth-token" }));
  };
  equal(await accessToken(account, fetchFn as typeof fetch, 1_000_000), "oauth-token");

  const [header, claims, signature] = assertion.split(".");
  const decode = (s: string) =>
    Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
  const text = (s: string) => JSON.parse(new TextDecoder().decode(decode(s)));
  deepStrictEqual(text(header), { alg: "RS256", typ: "JWT" });
  deepStrictEqual(text(claims), {
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: TOKEN_URI,
    iat: 1_000_000,
    exp: 1_003_600,
  });
  ok(
    await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      publicKey,
      decode(signature),
      new TextEncoder().encode(`${header}.${claims}`),
    ),
  );
  notEqual(signature, "");
});

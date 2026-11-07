import { deepStrictEqual, equal, notEqual, ok } from "node:assert/strict";
import {
  accessToken,
  createHandler,
  fcmMessage,
  type ServiceAccount,
  type Target,
  templateFor,
} from "./notify.ts";

const SECRET = "test-shared-secret";
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
function fakeFetch(targets: unknown[], fcmStatus = 200, fcmBody: unknown = {}) {
  const calls: Call[] = [];
  const fetchFn = (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, body: String(init?.body ?? "") });
    if (url === `${SUPABASE}/rest/v1/rpc/notification_targets_v2`) {
      return Promise.resolve(Response.json(targets));
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

async function handler(fetchFn: typeof fetch) {
  const { account } = await serviceAccount();
  return createHandler(
    {
      notifySecret: SECRET,
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
    "dns_hijack",
    "port_forward",
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

Deno.test("a body without a positive integer event id is rejected", async () => {
  const { calls, fetchFn } = fakeFetch([]);
  const handle = await handler(fetchFn);
  for (const body of [{}, { event_id: "1" }, { event_id: 1.5 }, { event_id: -1 }, null]) {
    equal((await handle(webhook(body))).status, 400);
  }
  equal(calls.length, 0);
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

# App design

The DeKoi Android app's design. Decisions behind it: ADR 0011. Data behind
it: ADR 0010. Wording rules: CLAUDE.md, "Writing".

## Principles

1. Answer first. Every screen opens with one sentence: is everything fine,
   and what to do next.
2. Plain words on the surface, detail one tap down. Addresses, ports and
   protocols live in collapsed "Technical details".
3. Colour never stands alone. Severity is always colour, icon and word.
4. Say only what DeKoi knows. Inferred kinds read "looks like a TV". The map
   shows what DeKoi sees, not cabling. DeKoi cannot block devices; the app
   explains how to block them in the router.
5. Calm motion. Motion explains a change. At most one idle loop on screen,
   only on a hero, none when the system animation scale is 0.
6. Attacker text is inert: host names, models, user names and service
   labels are plain `Text` only, never links, never in notifications, never
   the only label.

## Navigation

Four tabs, M3 `NavigationBar`, Material Symbols, filled when selected: Home
(`home`), Alerts (`notifications`, badge = critical and warning alerts in the
last 24 h not from known devices), Network (`lan`), Settings (`settings`).

Pushed screens: Alert detail, Device detail (a host), Networks, Your DeKoi,
Add DeKoi. Type-safe Navigation Compose routes (`@Serializable` objects).
Each tab keeps its back stack; back at a non-Home tab root goes Home;
reselecting a tab pops to its root and scrolls to top. Predictive back from
Navigation. A notification opens Alert detail over the Alerts tab.

Cross-links: Alert to Device; Device to Alerts filtered to it; decoy tile to
Alerts filtered to that service; Home tiles to their tabs.

## Screens

**Home.** (1) Status hero: 120 dp ring around a shield, coloured by
`Protection`, existing `status_*` strings, polite live region, ring draws in
once (700 ms). (2) "Needs attention": up to three critical or warning alert
cards (severity chip, title, device chip, "See what to do"); hidden when
none. (3) 2x2 tiles: devices on your network (count, "3 new this week"),
alerts in 7 days (count and sparkline), Your DeKoi (status, last check-in),
decoys ("8 running"); tabular figures roll on change (300 ms); one column
above font scale 1.5. (4) Seven-day bar chart stacked by severity; tapping a
bar opens Alerts for that day. (5) Latest five events. `PullToRefreshBox`
replaces the Refresh button.

**Alerts.** Filter chips: All, Act now, Check soon, For your information,
and a removable "From this device" chip. Sticky day headers. Row: 40 dp
severity tile, title (two lines max), subtitle "Looks like a phone · 14 times
· 21:14", chevron. Alerts from known devices are dimmed with "Known device".
Rows use `animateItem()`.

**Alert detail.** Severity-coloured container band with glyph, severity
word, title, time and count. Then: What happened; device card (kind tile,
name, "looks like", maker and model, address, network, online status; opens
Device detail); What to do (numbered steps from `Guidance.kt`, inline "Copy
hardware address"); "I know this device" outlined button after the steps,
with a confirmation for `arp_spoof`, `rogue_dhcp`, `rogue_router`,
`name_poisoning`; Technical details collapsed (`animateContentSize`,
monospace, copyable).

**Network.** Header "38 devices · 3 new this week · 1 needs attention" and a
"Networks" row. Map | List segmented control, choice saved; List is the
default with TalkBack on.
- Map: gateway at the centre; ring 1 infrastructure (switches, access
  points, DeKoi with the brand mark); ring 2 everything else clustered by
  kind. 40 dp tiles with a status dot (online green, not seen recently hollow
  grey), severity halo for recent alerts, "New" badge. Thin straight spokes,
  dashed when not seen recently. Over 40 devices: kind bubbles with counts,
  tap to zoom in. Pinch zoom, pan, double-tap reset. Footer: "Shows the
  devices DeKoi can see, not how they are connected." For TalkBack the map is
  one node with a summary and a "Show as list" action.
- List: search, sort (name, last seen, kind); sections Needs attention, New
  this week, Online, Not seen recently. Row: tile, name, "Looks like a TV · LG
  · 192.168.1.23", status.
- Display name: host name, else model, else maker plus kind ("LG TV"), else
  "Unknown device"; private addresses use `network_private`.

**Device detail.** (1) Hero: 96 dp kind tile with its idle animation, shared
element from list or map; name, "Looks like a TV", status pill ("Online now"
or "Last seen 2 h ago"), Known or New chip. (2) Attention card if it caused
an alert in 24 h. (3) 14-day sparkline and "See all alerts from this
device". (4) About: maker, model, system (`os`), announced name, service
labels as plain chips. (5) Connection: address, network, hardware address
(copyable), private-address note, first and last seen. (6) Known toggle.

**Networks.** One card per network: "Your home network" (contains DeKoi's
`local_ip`) or "Another network", the CIDR, the source in plain words
(interface: "DeKoi is connected to it"; router_advertisement: "Announced by
a router"; dhcp_route: "Reached through a router"; observed: "Seen in
traffic"), gateway, device count, a bar list of kinds. Footer: "DeKoi
watches only the network it is plugged into."

**Your DeKoi.** Hero (brand-mark tile, name, status, "Checked in 1 min
ago"); 7-day uptime strip, one segment per hour from `device_online` and
`device_offline` events; decoy grid, one tile per `Device.services` entry
(plain name, "Touched 3 times this week", sparkline); details (software
version, address, linked since); rename inline, Remove with confirmation;
the setup checklist while setting up. No CPU, temperature, disk or bandwidth:
the heartbeat does not report them.

**Settings.** Inset grouped list: Account (email, Sign out); Your DeKoi
devices; Add DeKoi; Notifications (channel state, opens system channel
settings); Privacy; About (version, open-source licences); Delete account
last, in red, with confirmation.

**Sign-in and onboarding.** Brand hero with a slowly floating koi mark (6 s,
static without animations), tagline, "Continue with Google", inline errors.
Add DeKoi: three-step card (prepare the card, scan, plug in); "Scan the code"
primary, "Type the code" secondary with a 26-character input in groups of
four. After a claim, a vertical timeline (linked, first start, online,
watching) ticks off as data arrives, polling every 15 s while visible; help
after 20 min; on success the ring completes, then the notification
permission request.

**States.** Loading: skeletons shaped like the content (no shimmer without
animations). Empty: glyph, one sentence, next step. Error: inline banner
above cached data with "Try again", never a dialog; action failures use a
snackbar. Stale (over 5 min): "Updated 10 min ago" under the title.

## Tokens

Every colour is defined once in `clients/shared/.../DesignTokens.kt`; the app
maps them to the M3 `ColorScheme` and `LocalSeverityColors`. No dynamic
colour.

| Token | Light | Dark |
| ----- | ----- | ---- |
| background | #F2F3F5 | #0E1013 |
| surface | #FFFFFF | #181A1F |
| raised | #FFFFFF, 3 dp shadow | #22252B, 1 dp #2E323A border |
| text | #15171C | #F3F4F6 |
| textSecondary | #5B616D | #A3A9B4 |
| textTertiary (decoration only) | #8A909B | #737A86 |
| separator | #DADDE2 | #2E323A |
| tint, on tint | #0A63D1, #FFFFFF | #5AA2FF, #0E1013 |
| tint container, on | #E5EFFC, #0A4FA8 | #16273F, #B8D6FF |
| critical | #C4282B | #FF6B66 |
| critical container, on | #FDECEC, #8E1B1E | #3A1A1B, #FFB4B0 |
| warning | #A85A00 | #FFB34D |
| warning container, on | #FFF2DD, #7A4100 | #3A2A12, #FFD49A |
| info | #4E5D73 | #9EACC0 |
| info container, on | #EBEFF5, #36445A | #232A35, #C9D3E1 |
| safe | #1B7F47 | #4ED18B |
| safe container, on | #E6F4EC, #145C34 | #15301F, #A6EBC2 |
| offline | #6B7280 | #9AA1AD |
| brand (logo, onboarding art) | #E8743B | #E8743B |

Severity words and glyphs: critical "Act now" `error`; warning "Check soon"
`warning`; info "For your information" `info`; safe "All clear"
`check_circle`. `Protection`: protected uses safe, attention the highest
recent severity, offline uses offline, setting up uses tint.

Type (system sans, `tnum` for numbers): displayMedium 44/48 SemiBold
(counts); displaySmall 34/40 Bold (screen titles); headlineMedium 28/34
SemiBold (status hero); headlineSmall 22/28 SemiBold (alert title);
titleMedium 17/22 SemiBold; bodyLarge 17/24; bodyMedium 15/21; labelLarge
15/20 SemiBold; labelMedium 13/18 Medium; addresses monospace 15. Layouts
survive 200 % font scale.

Spacing 4, 8, 12, 16, 20 (gutter), 24 (sections), 32. Touch targets 48 dp;
rows 56 dp, 64 dp with a tile. Radii: chips 8, pills full, 40 dp tiles 12,
cards 16, hero tile and sheets 28. Flat tonal elevation; shadows only on
floating elements. Motion: push is shared axis X (30 dp, fade, 300 ms); tab
switch fade-through 200 ms; expand spring (damping 1, stiffness 400).

## Device kinds

One table maps each ADR 0010 kind to a Material Symbol and an idle overlay
(Device detail hero only; at most 2 dp movement or 20 % alpha; off when the
animation scale is 0):

| Kind | Symbol | Idle |
| ---- | ------ | ---- |
| router | `router` | two LEDs blink alternately, 2 s |
| switch | `lan` | a dot travels the links, 3 s |
| access_point | `nest_wifi_point` | signal arcs expand and fade, 2.4 s |
| computer | `computer` | cursor blinks, 1 s |
| phone | `smartphone` | 2° tilt, 4 s |
| tablet | `tablet` | 2° tilt, 5 s |
| tv | `tv` | screen tint drifts, 6 s |
| media_player | `cast` | cast arcs fill in turn, 2.4 s |
| speaker | `speaker` | two sound arcs pulse, 1.6 s |
| game_console | `sports_esports` | one button pulses, 2 s |
| printer | `print` | paper edge slides 2 dp, 3 s |
| camera | `videocam` | recording dot blinks, 2 s |
| storage | `hard_drive` | activity LED flickers, 3 s |
| smart_home | `home_iot_device` | glow ring breathes, 4 s |
| wearable | `watch` | slow pulse, 2 s |
| server | `dns` | LEDs light top to bottom, 2.4 s |
| unknown | `device_unknown` | none |

## Alerts

Template: title naming the device kind; severity chip and one consequence
sentence; what happened (counts and times); which device; numbered steps
(find, disconnect by kind, secure), "I know this device" last, technical
details collapsed. Disconnect step by kind:

| Kinds | Step |
| ----- | ---- |
| phone, tablet, wearable | Ask who in your home owns it. On it, turn off Wi-Fi. |
| computer | Unplug its network cable or turn off its Wi-Fi. Leave it switched on. |
| tv, media_player, speaker, game_console, printer | Unplug it from power. |
| camera, smart_home | Unplug it or take out its batteries. Reset it and update its software before using it again. |
| storage, server | Shut it down from its app or power button, then unplug its network cable, so no files are lost. |
| router, access_point, switch | If you have more than one, unplug the extra ones one at a time. If this is the box from your internet provider, restart it instead. |
| unknown | If no one recognises it, block it in your router's app or settings page by its hardware address [Copy]. |

Never link to the router's web page: it is cleartext, and during ARP
spoofing it would hand the router password to the attacker.

## Notifications

FCM notification messages, as today (ADR 0011). `notify` picks the channel
by severity and names the source device's kind from fixed templates:

| Channel | Name | Importance | Carries |
| ------- | ---- | ---------- | ------- |
| `alerts_urgent` | Alerts to act on now | HIGH | critical |
| `alerts` | Alerts to check soon | DEFAULT | warning, device_offline |
| `updates` | For your information | LOW | info |

Severity colour as the notification colour, a monochrome DeKoi small icon,
`VISIBILITY_PRIVATE` with a public version "DeKoi: new alert about your
network", tag per source so repeats replace each other. No action buttons:
trusting or blocking needs the full context. Opening the alert in the app
cancels its notification.

## Libraries

Charts are Compose Canvas (bars, bar list, sparkline, uptime strip); no chart
library. Icons are Material Symbols Rounded vector drawables (weight 400,
grade 0, 24 dp; filled variants for selected tabs), Apache-2.0, copied from
google/material-design-icons at a pinned commit recorded in
`clients/app/src/main/res/README.md`. No Lottie, no Accompanist, no
bundled fonts.

## Avoid

Alarm styling and words ("hacked", exclamation marks, red everywhere),
security scores, pie charts, 3D, parallax; map lines that imply cabling;
data DeKoi does not have; attacker text in notifications or as links;
trusting or blocking from a notification or a swipe; animated list rows
beyond `animateItem`, more than one idle loop on screen; dynamic colour;
more than four tabs, hamburger menus, stacked dialogs.

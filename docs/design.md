# App design

The DeKoi Android app's design. Decisions behind it: ADR 0011 and, for
the brand, ADR 0020. Data behind it: ADR 0010. Wording rules: CLAUDE.md,
"Writing".

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
   only on a hero or the Features card in focus, none when the system
   animation scale is 0. One exception:
   a very likely attack opens with an alarm that pulses for 3 s, then holds
   still.
6. Attacker text is inert: host names, models, user names and service
   labels are plain `Text` only, never links, never in notifications, never
   the only label.

## Navigation

Four tabs, M3 `NavigationBar`, Material Symbols, filled when selected: Home
(`home`), Alerts (`notifications`, badge = unseen critical and warning
incidents not from known devices), Network (`lan`), Settings (`settings`).

Pushed screens: Alert detail, Device detail (a host), Networks, Insights,
Your DeKoi, Features, Add DeKoi, Place your devices. Type-safe Navigation
Compose routes (`@Serializable` objects). Each tab keeps its back stack;
back at a non-Home tab root goes Home; reselecting a tab pops to its root
and scrolls to top. Predictive back from Navigation. A notification opens
Alert detail over the Alerts tab.

Cross-links: Alert to Device; Device to Alerts filtered to it; decoy tile to
Alerts filtered to that service; Home tiles to their tabs.

## Screens

**Home.** Numbered sections ("01 / STATUS"), each label followed by a
hairline. (1) Status hero: 120 dp ring around a shield, coloured by
`Protection`, with a soft glow of that colour behind it; existing `status_*`
strings, polite live region, ring draws in once (700 ms), not on every
return; the words fade through when the status changes. Then, until a DeKoi
is fully set up or the owner closes it (remembered on the phone), the
welcome in a HUD frame: "Welcome to DeKoi", three numbered steps with
symbols (prepare the memory card, with this phone or DeKoi Setup; plug in
the network cable, then the power; watch it come online here), "Add a
DeKoi" and "Take the tour". Once closed, without a DeKoi, an "Add a DeKoi"
row. (2) "Needs attention": up to three critical or warning alerts of the
last 24 h, one per source device, on quiet hairline-edged cards (severity
chip, title, device, "See what to do"), each opening its alert; hidden when
none. (3) 2x2 tiles: devices on your network (count, "3 new this week"),
alerts in 7 days (count and sparkline), Your DeKoi (status, last check-in),
checks ("All 8 checks on", amber when one fails, grey while
offline; opens Features); tabular figures roll on change (300 ms); one
column above font scale 1.5; under them an "Add another DeKoi" row unless
the welcome shows. (4) Seven-day bar chart stacked by severity;
tapping a bar opens Alerts for that day. (5) Latest five events.
`PullToRefreshBox` replaces the Refresh button.

**Alerts.** A calm timeline of incidents (ADR 0013), grouped by
`incidents()` in `Incidents.kt`: events from one DeKoi and one source, each
within 30 minutes of the one before; the most serious event leads and sets
the severity. In the app an incident is an "alert" and its events are
"times"; "event" appears only in Technical details. Filter chips: All,
Unread, and one "How urgent" chip with a menu; a removable "From this
device", decoy or day chip from a cross-link. Sticky day headers. No
cards: a hairline runs down the side with one dot per incident, and each
entry is the severity glyph and word, the lead event's title and "14 times
· 15:13–15:20", with a chevron. Unseen incidents are
bright: the dot glows, and the dot, glyph and word carry the severity
colour, nothing else does. Seen incidents are grey and quiet: a hollow grey
dot, grey glyph and word, a lighter title. Known devices are dimmed with
"Known device". Entries use `animateItem()`.

**Alert detail.** One incident; opening it marks its events seen
(`acknowledge_events_v1`) and clears their notifications. (1) A critical
incident that is very likely an attack opens with the alarm hero inside a
HUD frame (ADR 0020): the source's kind tile at the centre of rings that
pulse outwards in the critical colour for 3 s, then a steady glow (static
without animations). Everything else opens with a still, unboxed header:
warnings never move, info stays grey. Both carry glyph and severity word
in the severity colour, title, one consequence sentence, day, count and
time span. (2) "Is this a real threat?" from `threat()` in `Guidance.kt`:
"Very likely an attack" (a sign-in attempt, an opened bait file;
`arp_spoof`, `rogue_dhcp`, `name_poisoning`, `dns_hijack`, `evil_twin`),
"Possibly an attack" (scans, decoy connections, `rogue_router`, IDS alerts,
kinds the app does not know yet), "Probably harmless" (a known device,
DeKoi's own status), each with one plain sentence. (3) "Do this now" for
the alarm, else "What to do": the numbered steps as a checklist the owner
can tick (on the phone only, not stored), one action per step; a step that
names the hardware address has it below, copyable. Then "Mark as known",
outlined (undone with "Remove from known devices"), with a confirmation
when the incident includes `arp_spoof`, `rogue_dhcp`,
`rogue_router` or `name_poisoning`. (4) What happened: explanation,
specifics in plain words (decoys, well-known openings, a claimed router
address) and the events on a small timeline, the first eight, then "Show
each time (14)". Technical details give "First time" and "Last time". (5) Which device (kind tile, name, "looks like", maker and model,
address, network, online status; opens Device detail). (6) Technical
details collapsed (`animateContentSize`, monospace, copyable).

**Network.** Header "38 devices · 3 new this week · 1 needs attention", a
"Networks" row, an "Insights" row per DeKoi and an "Add DeKoi" row (also
before any device shows). Map | Rooms | List segmented control, choice
saved; List is the default with TalkBack on.
- Map: a radar in the HUD frame (ADR 0020). Gateway at the centre; ring 1
  infrastructure (switches, access points, DeKoi with the brand mark); ring
  2 everything else clustered by kind, or by room once any device has one
  (room names outside the ring). 40 dp round nodes with the kind glyph, a
  status dot (online filled, not seen recently hollow), severity halo for
  alerts in 24 h, "New" badge. Hairline spokes in the accent at low
  opacity, dashed when not seen recently. Over 40 devices: one bubble per
  cluster with its count; tapping it scales in to that cluster. Pinch zoom;
  pan once zoomed in (one finger otherwise scrolls the page); double-tap
  fits. Footer: "Shows the devices your DeKoi can see, not how they are
  connected." For TalkBack the map is one node with a summary and a "Show
  as list" action.
- Rooms: a plan of the home from the rooms the owner sets (ADR 0023), from
  `roomAreas()` and `floorPlan()` in `Rooms.kt`. One HUD-framed area per
  room in use: the suggested rooms in their order (Living room, Kitchen,
  Bedroom, Office, Hallway, Garage, Other), then the owner's own
  alphabetically, matched whatever their case. Two areas to a row, a row
  sharing the height of the tallest; a room with more than four devices
  takes a whole row, and one column above font scale 1.5. Each area: its
  name as a label, the count in blue, the devices as 40 dp kind tiles in
  kind order with status dot and alert halo, the name under each. Then
  "Not placed yet" under a hairline, with "Place your devices". Before
  any room is set, one sentence says what the view becomes. Footer: "You
  choose the rooms. Your DeKoi does not know where a device is." Nothing
  moves. TalkBack reads each area as a heading ("Living room, 3 devices")
  and each device with its status and any alert.
- Place your devices: the devices without a room when it opens, one at a
  time: the hero in the HUD frame (kind tile, name, maker, "Looks like a
  TV"), "Which room is it in?" with the rooms as chips in the plan's order,
  and Skip. A pick saves the room at once and keeps the name. "3 of 7"
  under the title; a polite live region reads the progress and each
  device. Ends with how many devices now have a room and "See your rooms".
- List: search, sort (name, last seen, kind); sections Needs attention, New
  this week, Online, Not seen recently, or By room once any device has one.
  Row: tile with status dot, name, "Looks like a TV · LG · 192.168.1.23",
  severity, Known device or New tag.
- Display name: the owner's name (ADR 0013), else host name, else model,
  else maker plus kind ("LG TV"), else "Unknown device"; private addresses
  use `network_private`.

**Device detail.** (1) Hero in the HUD frame: 96 dp kind tile with its idle
animation, shared element from the list, map or rooms; name, "Looks like a
TV", room, status ("Online now" or "Last seen 2 h ago"), Known device or New
tag. (2) Attention card if it caused an alert in 24 h, linking to that
alert. (3)
14-day sparkline and "See all alerts from this device". (4) About: maker,
model, system (`os`), announced name, service labels as plain tags. (5)
Connection: address, network, hardware address (copyable), private-address
note, first and last seen. (6) "Mark as known" (undone by "Remove from known devices") and "Name and room": a sheet
with both fields and suggested rooms (those in use, then common ones).

**Networks.** One card per network: "Your home network" (contains DeKoi's
`local_ip`) or "Another network", the CIDR, the source in plain words
(interface: "Your DeKoi is connected to it"; router_advertisement: "Announced by
a router"; dhcp_route: "Reached through a router"; observed: "Seen in
traffic"), gateway, device count ("None seen yet", never a bare 0), a bar
list of kinds. Until a DeKoi has reported any, one sentence says it does so
after its next automatic update, with its own address as "Your home
network". Footer: "Your DeKoi watches only the network it is plugged into."

**Insights.** One DeKoi's view of the network (ADR 0015), loaded each time
the screen opens. Hero in the HUD frame: the share of the last 7 days the
internet worked and the hourly uptime strip. Then numbered sections: (1)
Internet: router, internet and lookup rows with typical latency, loss and
a sparkline; times without internet, saying when the router itself did
not answer. (2) Web addresses: the DNS check with glyph and word, the name
server under Technical details; "See what to do" (the alert, else the
Learn topic) when answers differ. (3) Open to the internet: ports by
device with its kind tile, opening Device detail; port names are plain
text. (4) Your connection: public address (copyable) and country; why
there is no per-device bandwidth. (5) Speed: the owner's nightly test
switch and the results with a sparkline.

**Your DeKoi.** Hero in the HUD frame (fish mark, name, status, "Checked
in 1 min ago"). The setup checklist while setting up; otherwise "What DeKoi
watches" (ADR 0013): one row per monitor in plain words with the numbers
the DeKoi reports ("Checking traffic against 48,213 known attack patterns",
"Updated 3 Nov"), a green check when on, amber when failing, grey otherwise
and while offline; the software version and when it last updated; the
reported names collapsed under Technical details. Software from before
ADR 0013 lists only its decoys and says more detail follows the next
automatic update; no row is made up. "See all features" opens Features.
7-day uptime strip, one segment per
hour from `device_online` and `device_offline` events. Decoy grid, one tile
per `Device.services` entry (plain name, "Touched 3 times this week",
sparkline; opens Alerts for that service). "Try your decoy" while online
(ADR 0018): the storage login page at `http://<local_ip>` when `http` runs,
the file share at `\\<local_ip>` (Windows) and `smb://<local_ip>` (Mac)
when `smb` runs, each with where to type it, and that signing in sends an
alert. Details (name with inline rename, address, linked since); Remove
with confirmation. No CPU, temperature, disk or bandwidth: the heartbeat
does not report them.

**Features.** Everything DeKoi does, for the owner and for demos; from
Home's checks tile, "See all features" on Your DeKoi, and Settings, About.
It speaks for the DeKoi it was opened for, else the one Home speaks for.
The answer first ("All 12 features are on", "9 of 12 features are on", why
none run while the DeKoi is offline or setting up, "Add a DeKoi to turn
these on" without one), then "Show me around". One HUD-framed card per
`Feature` (`Features.kt`), in this order: decoy file server, intrusion
detection, scan detection, ARP Guard, Router Guard, name trap, Wi-Fi
Guard, network map, new device alerts, internet check, open to the
internet, weekly summary. A card holds a 56 dp Canvas drawing of the
feature at work, its name, a one-line promise, the status, "Last found 2
hours ago" with the newest related event's action (opens that alert), and
links: "Learn more" (`learnTopicFor` of its first event kind) and its own
place (Settings for the Wi-Fi name and the weekly summary, the map,
Insights). The decoy card names the decoy as Windows shows it
("FAMILY-NAS", from `device/canary/persona/persona.json` at build time)
and, while the DeKoi is online, "Try it" with its addresses.
- Status in glyph, colour and word, from data only (`featureState`):
  monitors from the heartbeat (ADR 0013), the more concerning of two for
  scan detection and Router Guard; Wi-Fi Guard's reported state, after
  "Needs your Wi-Fi name" (a link to Settings) while the owner has given
  none; `public_ip`, which every hourly report sets (ADR 0015); the
  owner's weekly summary setting. On; "On, learning what is normal" (new
  devices in the first day of the inventory, Wi-Fi Guard learning);
  Starting; Needs your Wi-Fi name; "After the next automatic update" for
  software that does not report the feature, unless the DeKoi started or
  updated within 2 hours (Starting); Paused while offline; Turned off; Not
  working right now; Status not known. Software from before ADR 0013 counts
  its decoys and its device list as on, nothing else. Under the word, while
  it holds, what the DeKoi reports: rule count and ruleset date, decoys,
  devices, new this week, the DNS check, when the router's port list was
  read, what Wi-Fi Guard is doing.
- Motion: only the drawing of the card nearest the middle of the screen
  loops, slowly; every drawing has a still frame that tells the story on
  its own, shown for the rest and without animations.
- Tour: "Show me around" scrolls each card in turn to the top, lights its
  frame and dims the others, with a caption on navy over the bottom of the
  screen (number, name, one or two sentences, polite live region), Skip and
  Next. It moves on after 5 s, or the longer time the accessibility
  settings ask for, with a blue line along the caption's top edge showing
  when; without animations or with TalkBack it waits for Next and cards
  jump instead of scrolling. Back ends it.

**Settings.** Inset grouped list: Account (email, Sign out); Your DeKoi
devices; Add DeKoi; Wi-Fi Guard (each DeKoi's home Wi-Fi name and what
Wi-Fi Guard is doing, ADR 0019); Notifications (channel state, opens system
channel settings); the weekly summary; Privacy; About (version, Features,
Learn, the tour, open-source licences); Delete account last, in red, with
confirmation.

**Sign-in and onboarding.** Brand hero with a slowly floating koi mark (6 s,
static without animations), tagline, "Continue with Google", inline errors.
The tour (four pages over the horizon) opens once per tour version after
sign-in, so a changed tour reaches existing owners once; its last page
offers "Add a DeKoi" without one, "Done" otherwise; Settings keeps "Take the
tour". Add DeKoi: three-step card (prepare the card, scan, plug in); "Scan
the code" primary, "Type the code" secondary with a 26-character input in
groups of four. After a claim, a vertical timeline (linked, first start,
online, watching; `SetupTimeline`, the same as on Your DeKoi) ticks off as
data arrives, polling every 15 s while visible; help after 20 min; on
success the ring completes, then the notification permission request.

**States.** Loading: skeletons shaped like the content (no shimmer without
animations). Empty: glyph, one sentence, next step. Error: inline banner
above cached data with "Try again", never a dialog; action failures use a
snackbar. Stale (over 5 min): "Updated 10 min ago" under the title.

## Tokens

The brand is the CyberFish guide (`UX/UI/CyberFish-aesthetic.md`); ADR 0020
says how the apps adopt it, and the guide wins where this file differs.
Dark only, no dynamic colour. Every colour is defined once in
`clients/shared/.../DesignTokens.kt`; the app maps them to the M3
`ColorScheme` and `LocalSeverityColors` in `ui/Theme.kt`, DeKoi Setup to its
own small scheme.

| Token | Value | M3 roles | Use |
| ----- | ----- | -------- | --- |
| background | #060A14 | background, surface | pages |
| navy | #0B1636 | primaryContainer, surfaceContainer* | glows, fog, what floats (menus, dialogs, snackbars), tracks |
| blue | #2EA8FF | primary, secondary, tertiary | lines, glows, labels; never a large fill, under a tenth of a screen |
| white | #F2F4FA | heading colour, onPrimaryContainer | headings, the wordmark, primary button labels |
| text | #C9D1E3 | onBackground, onSurface | body text |
| mid grey | #8A94AB | onSurfaceVariant, outline | secondary text, labels, control borders |
| dim grey | #4A5368 | none | decoration only: 2.6:1 is too faint for text |
| hairline | #1B2234 | outlineVariant | dividers, list lines, chart grids, tracks |
| offline | mid grey | | a DeKoi or device not seen recently |

Severity is the only other colour. Each container is its colour at 14 %
over the background; all text pairs reach 4.5:1 (`DesignTokensTest`):

| Severity | Colour | Container, on | On background, navy, container |
| -------- | ------ | ------------- | ------------------------------ |
| critical | #FF6B66 | #29181F, #FFB4B0 | 7.1, 6.4, 6.1 |
| warning | #FFB547 | #29221B, #FFD99E | 11.3, 10.1, 8.9 |
| info | #A9B8D4 | #1D222F, #D5DDEC | 9.9, 8.9, 7.9 |
| safe | #4ED18B | #102625, #A6EBC2 | 10.2, 9.2, 8.2 |

Severity words and glyphs: critical "Act now" `error`; warning "Check soon"
`warning`; info "For your information" `info`; safe "All clear"
`check_circle`. `Protection`: protected uses safe, attention the highest
recent severity, offline uses offline, setting up uses blue.

Type: two bundled OFL fonts (`res/README.md`), no synthesised bold or
italic. Michroma for display, headline and title styles: white, tracking
0.02 em, upper case through `headingCase()`, which keeps `DeKoi` as written.
JetBrains Mono 400 and 500 for body and label styles; its digits are
tabular by design (Michroma has no tabular figures; its digits differ by at
most 5 %). ExtraBold is for the mark only.

| Style | Font, sp | Use |
| ----- | -------- | --- |
| displayMedium | Michroma 32/40 | counts |
| displaySmall | Michroma 20/28 | screen titles |
| headlineMedium | Michroma 18/26 | status hero |
| headlineSmall | Michroma 16/24 | alert title, dialog title |
| titleMedium | Michroma 15/22 | |
| bodyLarge, `address` | Mono 16/24 | body, addresses |
| bodyMedium | Mono 14/20 | secondary lines |
| labelLarge | Mono 500 14/20 | buttons |
| labelMedium | Mono 500 12/16 | chips, chart labels |
| `label` | Mono 500 12/16, tracking 0.13 em, upper case | section labels, tab labels, HUD text |

Michroma is wide, so headings are smaller than system-font headings: at
200 % font scale on a 360 dp phone the longest tab title, NETWORKS, takes
309 of 320 dp and "PROTECTED" at 18 sp 287 dp. Tab labels shrink to fit
their tab rather than break mid-word. Layouts survive 200 % font scale.

Spacing 4, 8, 12, 16, 20 (gutter), 24 (sections), 32.
Touch targets 48 dp; rows 56 dp, 64 dp with a tile. Structure is hairlines
and space, not cards. Radii: chips 8, 40 dp tiles 12, hero tile and sheets
28; HUD frame and primary button 8 dp 45-degree cuts. No shadows; glows are
coloured blurs behind lines. Motion: push is shared axis X (30 dp, fade,
300 ms); tab switch fade-through 200 ms; expand spring (damping 1,
stiffness 400).

## Components

`ui/components/` holds the shared pieces; screens build on them.

- `InsetSection` and `InsetRow`: a hairline list; a `label` above, a line
  above every row and one closing the list. `KeyValueRow`, `CopyableRow`
  and `TechnicalDetailsRow` (the collapsed "Technical details") build on
  the row.
- `StatTile`: an open tile, a hairline along the top with a 24 dp blue lead,
  a `label`, the value.
- `SeverityChip`, `SeverityTile`: outline and glow in the severity colour
  over its container, never a solid fill.
- `PrimaryButton(onClick, modifier, enabled, content)`: the one primary
  action, an outlined, lit control (blue hairline, blue glow, navy inside,
  white label, cut corners). Secondary actions are M3 `TextButton`. M3
  `Button` is not used: its fill is primary, which is blue.
- Tab bar: background, a hairline along the top, a blue line over the
  chosen tab, white for the chosen icon and label, mid grey for the rest.
- `SegmentedControl`: segments over a hairline, a blue line under the
  chosen one.
- `StatusRing`: a 4 dp ring with a soft glow on a hairline track.
- Charts: blue lines and severity colours on a hairline grid.
- `HudFrame(modifier, content)`: one hairline of blue at 15 % with 8 dp
  45-degree cut corners and 16 dp inside; marks a hero area.
- `Horizon(modifier)`: a glowing blue line near the top with faint lines
  below that spread out towards the bottom, like a dark sea. A shimmer
  drifts along the line every 16 s; still when animations are off. For
  sign-in, onboarding and empty states.
- `FishMark(height, modifier)`: the `><>` mark in JetBrains Mono ExtraBold,
  white with a blue stroke; [height] is the ink's height. Glows from 48 dp,
  flat below. `BrandTile` is the mark on navy.
- `Wordmark(height, modifier)`: the mark at the cap height of Michroma
  "DeKoi", 0.6 em apart; [height] is the cap height.
- `SectionLabel(number, text, modifier)`: "01 / NETWORK", number in blue,
  text in mid grey, `label` style; a heading that TalkBack reads without
  the number.

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

Template: title naming the device kind; severity and one consequence
sentence; whether it is a real threat; numbered steps (find, disconnect by
kind, secure); "Mark as known"; what happened (counts and times); which
device; technical details collapsed. Disconnect steps by kind, one action
per step (a slash separates steps):

| Kinds | Steps |
| ----- | ----- |
| phone, tablet, wearable | Ask who in your home owns it. / On it, turn off Wi-Fi. |
| computer | Unplug its network cable or turn off its Wi-Fi. Leave it switched on. |
| tv, media_player, speaker, game_console, printer | Unplug it from power. |
| camera, smart_home | Unplug it or take out its batteries. / Reset it and update its software before using it again. |
| storage, server | Shut it down from its app or power button, so no files are lost. / Then unplug its network cable. |
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

## Motion

Code: `clients/app/.../ui/motion`. Push and pop use shared axis X with
fade-through fades (the old screen is gone in the first 35 %); a back
gesture seeks the pop linearly with a crossfade so the previous screen
shows at once. Shared elements: a host's kind tile and an alert's severity
move into the hero of their detail screen, only while that screen is pushed
or popped, never on a tab switch. Status changes fade through; items of a
plain list expand and fade with the expand spring; lazy rows use
`animateItem()`. Haptics use the platform constants (toggle on or off,
confirm) and long press, the firm click, once when a critical alert opens;
the system touch feedback setting applies. Under reduced motion nothing
moves and nothing vibrates.

## Libraries

Charts are Compose Canvas (bars, bar list, sparkline, uptime strip); no chart
library. Icons are Material Symbols Rounded vector drawables (weight 400,
grade 0, 24 dp; filled variants for selected tabs), Apache-2.0, copied from
google/material-design-icons at a pinned commit recorded in
`clients/app/src/main/res/README.md`. Fonts are Michroma and JetBrains Mono
(OFL-1.1), bundled, with their sources, hashes and licence texts recorded
in the same file. No Lottie, no Accompanist.

## Avoid

Alarm styling and words ("hacked", exclamation marks, red everywhere),
except the alarm hero of a very likely attack: one strong signal for real
alarms, calm for everything else; warnings never move, info stays grey;
security scores, pie charts, 3D, parallax; map lines that imply cabling;
data DeKoi does not have; attacker text in notifications or as links;
trusting or blocking from a notification or a swipe; animated list rows
beyond `animateItem`, more than one idle loop on screen; dynamic colour;
more than four tabs, hamburger menus, stacked dialogs.

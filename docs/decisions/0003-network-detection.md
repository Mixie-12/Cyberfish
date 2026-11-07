# 0003: Network detection beyond the decoys

Status: accepted, 2026-11-07

## Context

The owner wants the most detection that fits in one plug-in box: not only a
honeypot, but detection of network probing and scanning, ARP spoofing and
other suspicious activity, all shown in the app.

A device plugged into a switched home network sees only three kinds of
traffic: packets addressed to it, broadcast and multicast frames (ARP, DHCP,
IPv6 neighbour discovery and router advertisements, mDNS, LLMNR, NetBIOS), and
the answers to its own probes. It cannot see unicast traffic between other
devices unless the switch mirrors it to the device. A full network IDS on every
packet would need a managed switch with a mirror port, which breaks
plug-and-play.

## Decision

Three layers, each off the shelf where something exists:

1. Decoys: OpenCanary (ADR 0001). Any touch is a strong signal.
2. IDS: Suricata in its own container (`dekoi-ids`) on the host interface,
   with the Emerging Threats Open ruleset. It inspects everything that
   reaches the box: scans of every kind, exploit attempts against the
   decoys, known malware behaviour. If the owner later connects DeKoi to a
   mirror port, the same setup inspects the whole network; that is a later
   option, not the default.
   - The ruleset is fetched and tested with `suricata -T` at image build
     time, so rules reach devices through the signed image (ADR 0004) and
     roll back like any update. The weekly rebuild keeps them current.
   - Alerts go to a Unix datagram socket the agent owns, not to a file:
     no SD-card wear and nothing to rotate. Suricata reconnects by itself.
   - Runs as uid 10003 with only CAP_NET_RAW and CAP_NET_ADMIN, read-only
     root, 1 GB memory limit. Only alerts of high and medium severity become
     events, and only rule metadata, never payload.
   - Modbus and DNP3 rules are disabled: their parsers are off, those
     protocols do not occur on home networks, and enabling them would only
     add attack surface.
3. LAN watchers in the agent, for attacks Suricata does not cover, using a raw
   packet socket the agent already has (CAP_NET_RAW) and plain sockets:
   - ARP: network sweeps (one host asking for many addresses), spoofing (an
     address claimed by a second hardware address while the first is alive,
     especially the router's).
   - DHCP: rogue DHCP servers (a periodic DHCP discover; answers from more than
     one server, or from a server other than the router).
   - IPv6: rogue router advertisements (from a router other than the known
     one).
   - Name poisoning: periodic LLMNR, NBT-NS and mDNS queries for names that do
     not exist; any answer means a poisoner such as Responder is active.

Each finding is a new event kind with plain-language text in the app. The
device API accepts the new kinds additively; old agents are unaffected.

## Consequences

- Suricata needs memory: Raspberry Pi 4 with 2 GB is the minimum, 4 GB
  recommended. Rules are at most a week old.
- The end-to-end test covers the IDS starting locked down under real Podman
  and the ARP sweep detector; alert delivery over the socket is covered by
  unit tests. A live alert is verified on hardware.
- Suricata alerts carry rule text written by the ruleset authors, not by the
  attacker; payload excerpts are never sent to the backend.
- Routers that sweep their own network for discovery will trigger the sweep
  rule once; the owner marks them as known in the app.
- The app must say plainly what DeKoi can and cannot see, so the owner does not
  assume full coverage.
- Detection of traffic between other devices (lateral movement that never
  touches DeKoi or a broadcast) is out of scope until the mirror-port option.

# 0023: A home plan from the rooms the owner sets

Status: accepted, 2026-11-07

## Context

The owner asked for a map of where all their devices are in the home.
DeKoi sees addresses and traffic, not positions: signal strength would
need the router's help, and nothing it sees says which room a device is
in. The app already stores a room per device (ADR 0013), set in Device
detail one device at a time.

## Decision

- The "map of locations" is a plan built only from the rooms the owner
  sets: a third Network view, Rooms, next to Map and List. No positions,
  floor shapes or drag and drop: they would need new columns, an editor
  and an API change for little more than the room already says.
- The plan is laid out by pure functions (`ui/network/Rooms.kt`): the
  suggested rooms in a fixed order, then the owner's own alphabetically,
  matched whatever their case; two small rooms to a row, a room with more
  than four devices on a row of its own; devices without a room last,
  under "Not placed yet".
- "Place your devices" goes through the devices without a room one at a
  time and saves each pick through the existing `network_hosts.room`
  write. No backend change.

## Consequences

- The plan is only as right as the owner's rooms, and the view says so.
- The Rooms view merges rooms that differ only in case; List's "By room"
  still groups them as typed.
- Garage and Other join the suggested rooms in every language; lint fails
  a translated list with fewer rooms.

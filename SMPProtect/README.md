# SMP-Protect

Protects areas of the server with **regions** and **flags** (Paper 1.21.11, Java 21). Admin-defined regions; the hub is the
first use case. Without a flag, everything stays vanilla (allowed).

## Install

Copy `build/libs/SMP-Protect-1.0.0.jar` into `plugins/` and restart. Regions are kept in `plugins/SMP-Protect/regions.yml`.
If that file is unreadable the plugin disables itself loudly instead of silently dropping protection.

## Protect the hub in four commands

```
/protect define hub world hub        # the whole world named "hub" (or select a cuboid, see below)
/protect preset hub hub              # no building, breaking, containers, PvP, damage, hunger, mob spawning, griefing, explosions, fire
/protect addmember hub <player>      # optional: people who may still build there
/protect info hub
```

Staff with `smpprotect.bypass` (default op) are never stopped, so they can keep building the hub.

## Cuboid regions

`/protect wand` gives a blaze rod: left click = corner 1, right click = corner 2 (or stand somewhere and use
`/protect pos1` / `pos2`), then `/protect define <name> [fullheight]`. `/protect redefine <name>` changes the bounds.

## Commands (permission `smpprotect.admin`, default op; aliases `/rg`, `/region`)

`wand`, `pos1`, `pos2`, `define <name> [fullheight | world [worldName]]`, `redefine <name>`, `remove <name>`,
`list [world]`, `info [name]` (no name = the regions where you stand), `flag <name> <flag> <allow|deny|none>`, `flags`,
`priority <name> <n>`, `addowner|removeowner|addmember|removemember <name> <player>`, `preset <name> hub`, `reload`.

## Flags

| Flag | Controls | Owners/members exempt |
|---|---|---|
| build | placing blocks, buckets, boats, minecarts, armor stands, frames, spawn eggs, flint and steel, bone meal | yes |
| break | breaking blocks, frames, paintings | yes |
| interact | doors, buttons, levers, redstone, farmland trampling, villagers, horses | yes |
| container | chests, barrels, furnaces, hoppers, shulker boxes, lecterns, chest minecarts | yes |
| entity-damage | hurting animals, villagers, vehicles; hitting, rotating, filling or stripping armor stands and item frames (monsters can always be hit) | yes |
| pvp | players hurting players (checked at both players) | no |
| damage | any damage to players (void damage still works) | no |
| hunger | losing food | no |
| mob-spawn | natural spawns, reinforcements, raids, patrols (spawners, eggs, commands are not affected) | no |
| mob-grief | endermen, ravagers, withers, zombies breaking doors... | no |
| explosion | explosions break no blocks and no hanging items inside | no |
| fire | fire spread, burning, lava ignition | no |

## How regions combine

Where regions overlap, the one with the **highest priority** that has the flag set decides; at equal priority the
**smaller** one does. A region that does not set the flag is skipped, so a big region can set defaults and a small
higher-priority one can allow things inside it. No region with the flag = allowed. Pistons, flowing water/lava cannot
move blocks across the edge of a region that denies build/break unless the piston/fluid source is in the same regions.

## Reliability

Every change is written to `regions.yml` (temporary file, then atomic replace) **before** it is applied in memory, so a
failed save changes nothing. `/protect reload` with an invalid file keeps the regions that were running.

## Greeting and farewell

`/protect greeting <region> <MiniMessage text>` shows the text when a player enters the region, `/protect farewell <region>
<text>` when they leave (`none` removes it; tags `<region>` and `<player>`). Shown above the hotbar by default; change
`messages.greeting-display` to `title` or `chat`. Works for moving, teleporting and changing world.

## For other plugins

`SMPProtectPlugin#isProtectedSpot(world, x, y, z)` tells whether building is denied at a block. SMP-RTP uses it (through
reflection, so neither plugin needs the other) to never drop a player into a protected area.
## Known limits

- Dispensers placing liquids or igniting, TNT cannons that start outside, and other plugins that change blocks without
  firing Bukkit events are not covered. Other plugins' own checks (e.g. a plugin that cancels an event first) are
  respected, never overridden.
- `interact` uses Paper's list of clickable blocks; unusual modded or future blocks may need a flag test.
- Mounting and leashing count as `interact`.
- Regions are cuboids; there are no polygons, no entry/exit messages and no per-player claims.

## Build and test

```
gradlew.bat build        # -Xlint:all -Werror plus the unit tests
tools/integration-test.sh <server-folder> <work-dir> <HavenBuilder.jar>   # boots a throwaway server, 3 runs
```

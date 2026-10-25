# SMP-Catch 1.0.0 (author: im4theone)

Catches x-ray cheaters, plus staff vanish and freeze. Paper 1.21.11, Java 21.

## Decoys ("honeypots")
- `/trap vein <diamond|netherite|emerald|gold|iron|lapis|redstone|copper|coal|quartz|nethergold> [count] [radius] ` places fake ore
  veins around you. They go ONLY into solid rock that has rock on every side, so no cave, tunnel, water or lava can show them to an honest
  player: only x-ray sees them. (The nether has debris/quartz/nethergold, the overworld the rest.) From the console add `<world> <x> <z>`.
- `/trap stash [chest|barrel|shulker] [radius]` builds a sealed 3x3 room in the rock with a chest full of bait (diamonds, scrap, gold, emeralds,
  enchanted golden apples; configurable). The bait carries an invisible mark.
- Fake ore drops the normal items, so an honest player who stumbles on one loses nothing and a cheater notices nothing.
- `/trap list`, `/trap tp <id>`, `/trap remove <id>`, `/trap removeall confirm` (the rock is put back).
- Optional `auto-seed`: a chance for every newly generated chunk to get a decoy (off by default).

## How a cheater is judged
- Mining a fake ore that was still completely inside rock = points. A fake ore that was already open to a cave or tunnel counts as
  visible = no points, so honest strip miners are not punished.
- Opening a stash = points; breaking its chest = more; taking bait out = more. `/trap bait <player>` shows how much bait someone holds.
- Mining statistics: if too many of the last 300 rock/ore blocks were rare ores that were hidden in rock (default 4 %, natural luck is
  about 0.5 %), they get points as well.
- Points fade by 0.5 per hour. Staff with `smpcatch.alerts` get a message (click to prefill `/tp`), also in the console and `alerts.log`.
  At two point levels (6 and 12) you can run console commands, for example `freeze %player%` or a tempban (empty by default: alert only).
- `/trap suspects`, `/trap stats <player>`, `/trap forgive <player>`, `/trap alerts` (your alerts on/off).
- Staff who must work near decoys: give them `smpcatch.bypass`.

## Vanish and freeze
- `/v` (or `/vanish`): toggle; `/v on|off|list|<player>`. Vanished staff are invisible, removed from other players' tab list and from the
  server list count, make no join/quit/advancement/death messages (others get a fake "left the game"/"joined the game"), take no damage, are
  not targeted by mobs, pick nothing up and trigger no pressure plates. SMP-Scoreboard leaves them out of the online counts and marks them
  `[V]` for staff. Vanish survives reconnects and restarts. `smpcatch.vanish.see` lets staff see vanished players.
- `/freeze <player>`: the player cannot move, build, hit, drop or use commands (only chat and /msg); staff are told if they log out.

## Honest limits
Only the data and logic is unit tested (15 tests) and decoys were placed, found and removed on a real throwaway server. Vanish, freeze and
the cheater alerts were not tried with connected players. Cheat clients that scan the world for ALL ores will also light up decoys, which is the point,
but a very careful cheater who only digs to visible ore will not be caught by decoys; the statistics are the second net.
Config: `plugins/SMP-Catch/config.yml`. Test script: `boot-test.sh`.

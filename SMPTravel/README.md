# SMP-Travel

The travel basics for a survival server with a hub (Paper 1.21.11, Java 21): spawn and hub, homes, teleport requests and
`/back`. Every teleport has a **5 second stand-still warm-up for everyone** (moving or taking damage cancels it).

## Install
Copy `build/libs/SMP-Travel-1.0.0.jar` into `plugins/` and restart. Homes and the spawn/hub spots are saved in
`plugins/SMP-Travel/travel.yml`. If that file is unreadable the plugin disables itself instead of letting players overwrite
their saved homes.

## Commands
| Command | Does |
|---|---|
| `/spawn`, `/hub` | teleport to the survival spawn / the hub (until you set them, the world spawn of `world` / `hub`) |
| `/setspawn [hub]` | admin (`smptravel.admin`): the spawn (or hub) is now where you stand |
| `/sethome [name]`, `/home [name]`, `/homes`, `/delhome <name>` | homes (names a-z 0-9 _ -, up to 16 characters; default name `home`) |
| `/tpa <player>`, `/tpahere <player>` | ask to teleport to someone / ask them to come to you |
| `/tpaccept`, `/tpdeny`, `/tpcancel` | answer or cancel a request (valid 60 s; 10 s between requests) |
| `/back` | go back to where you were before your last teleport or death (`smptravel.back`, default everyone) |

New players start in the hub (`first-join-hub`), without a warm-up.

## Respawning
By default **everybody who dies comes back in the hub**, even with a bed (`respawn.mode: hub`). The spot saved with `/setspawn hub` is used
if there is one; otherwise the location in the config: the world `hub` at `0.5 100 0.5` (the middle of the hub plaza). `mode: bed` keeps a bed
or respawn anchor and sends everybody else to the hub, `mode: vanilla` leaves respawning alone. If the hub world is not loaded, respawning stays
vanilla so nobody is sent into the void. The place they died is remembered for `/back`.

## Home limits
`homes.default` (1) for everyone. Give more with the permission `smptravel.homes.<number>` (1 to 100), for example
`smptravel.homes.5`, or `smptravel.homes.unlimited`. The highest one a player has counts. See
`LuckPerms-setup/haven-new-plugins.txt` for per-rank lines.

## Safety
- `/home` and `/back` refuse if something solid now fills the saved spot.
- Saving writes a temporary file first, then replaces the real one, so a crash cannot leave a half-written `travel.yml`;
  a save that fails changes nothing in memory.
- A request is checked again when answered: the sender must still be online.

## Tested
Unit tests: homes and server spots (round trip, replace, delete, failed save, damaged file), request logic (expiry,
replacing, cancel), config validation, that every message the code uses exists in the config, home limits. A throwaway
server boots it and refuses to start with a broken `travel.yml`. The teleports themselves (warm-up, `/tpa`) need a
connected player and were **not** tested in game.

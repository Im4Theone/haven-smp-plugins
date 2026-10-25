# SMP-RTP

Random teleport in the **overworld, the nether and the end** (Paper 1.21.11, Java 21).

## Install
Copy `build/libs/SMP-RTP-1.0.0.jar` into `plugins/` and restart. Edit `plugins/SMP-RTP/config.yml`, then `/rtp reload`
(an invalid config is rejected and the old settings stay; at startup it disables the plugin and lists every problem).

## Use
| Command | Does |
|---|---|
| `/rtp` | random teleport in the `default-world` (overworld) |
| `/rtp overworld`, `/rtp nether`, `/rtp end` | random teleport in that world (aliases `/randomtp`, `/wild`) |
| `/rtp scout <world>` | admin: finds a safe spot and prints it, nobody is teleported |
| `/rtp reload` | admin: reload the config |

Flow: checks (permission, cooldown, world loaded) -> warm-up (stand still, 5 s for everyone, ops included; moving or damage cancels) -> search ->
payment (if the world has a cost) -> teleport. The cooldown only starts after a successful teleport.

## Where it sends you
Each world searches a **5000 x 5000 square** around its centre (`size: 5000`, so 2500 blocks each way), staying at
least `min-radius` from the centre (overworld 500, nether 200, end 1200: the end main island is about 100 blocks
wide, the outer islands start roughly 1000 blocks out). Spots are
tried in rounds of 6 chunks loaded in parallel (8 rounds by default). The world border is respected.

A spot is accepted only if: the ground is solid and harmless (no water, lava, magma, cactus, campfire, powder snow, leaves,
slabs, stairs, bedrock...), the two blocks above are air (or grass/ferns/snow layer you can walk through), and there is no
lava, fire, cactus or similar in the 3x3 columns around it. The overworld uses the highest block of the column, so you never
land in a cave or under a tree; the nether and end scan down between `min-y` and `max-y` (nether 32..120, below the roof).

## Permissions
`smprtp.use` (default everyone), `smprtp.world.overworld|nether|end` (everyone), `smprtp.bypass.cooldown|cost` (op; nobody can skip the warm-up),
`smprtp.admin` (op). Remove `smprtp.world.end` from a group to keep the end closed for them.

## Cost
`worlds.<name>.cost` (needs Vault and an economy, e.g. your SMP-VaultBridge). The money is taken right before the teleport
and paid back if the teleport fails; if the refund itself fails the console logs it loudly. 0 = free.

## Added later

- `/rtp player <online player> [world]` (admin): sends someone to a random spot with no warm-up, cooldown or cost.
- Cooldowns are now saved in `cooldowns.yml` and survive restarts.
- With SMP-Protect installed, random spots inside regions that deny building are skipped (`avoid-protected-regions`).
## Limits
- Chunks are generated where nobody has been, so the first teleports to far-away places take a few seconds and grow the world.
- Cooldowns are kept in memory and reset on restart.
- It does not look at SMP-Protect regions: a random spot could fall inside a protected area (the 500-block minimum keeps
  it away from a hub/spawn).

## Test
`gradlew.bat build` (unit tests) and `tools/integration-test.sh <server-folder> <work-dir>` (throwaway server on real terrain).

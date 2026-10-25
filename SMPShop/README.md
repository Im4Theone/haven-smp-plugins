# SMP-Shop 1.0.0 (author: Kaloyan)

Original shop for Paper 1.21.11 (Java 21). Replaces the downloaded DonutShop.

- `/shop`: End / Nether / Gear / Food pages for money (Vault = SMP-Economy) and the Shard Shop. Pick an item, choose the amount
  (1/10/max buttons), confirm. Prices are per item in `config.yml` (up to 2 decimals).
- Shards: the playtime currency. Every `interval-minutes` each online player has `chance-percent` to get 1. `/shards` shows
  yours; admins: `/shards give|take|set <player> <n>`. Stored in `shards.yml` (same format as before, copied from
  `plugins/DonutShop/shards.yml` if missing).
- Shard shop: crate keys (delivered by SMP-Crates, saved to the account) and spawners (delivered by SMP-Spawners).
- Safe ordering: money/shards are taken first, goods second; what does not fit is refunded exactly; a payment the economy did
  not confirm is never refunded or retried. Saves are atomic.
- Other plugins reach the balances through `getShardManager()` (SMP-Scoreboard shows them, SMP-Crates can give them).

Not carried over: the Zombified Piglin spawner (SMP-Spawners has no such creature unless you add it to both configs).
Tests: `./gradlew build`; `boot-test.sh` boots a throwaway server.

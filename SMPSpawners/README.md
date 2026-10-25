# SMP-Spawners 1.0.0 (author: Kaloyan)

Original stacked spawners for Paper 1.21.11 (Java 21). Replaces the downloaded ISpawners.

- Stack spawners of one creature in one block (right-click with the item; sneak = whole hand).
- A stacked spawner makes no mobs. It stores items and experience, up to `max_items * max_items_multiplier * stack`.
- Empty hand + right-click opens the menu: storage (take one stack, shift-click takes all that fits), SELL ALL
  (paid through Vault, so SMP-Economy), collect experience.
- Breaking needs silk touch (configurable); sneak breaks a batch. The last spawner pays out the stored loot and XP.
- Explosions and pistons leave stacked spawners alone.
- Saves are atomic; if a save fails nothing is given and nothing is lost. A payment that times out is never retried.

Commands (`/spawners`, aliases `/ispawners`, `/isp`; permission `smpspawners.admin`):
`info`, `give <player> <creature> [amount]`, `givespawner <creature> [amount]`, `setstacksize <n>`, `reload`.

Compatibility: the config keys, `spawners.yml` format and the spawner item tag are the same as ISpawners', so existing
spawners and items keep working. If `plugins/SMP-Spawners/spawners.yml` is missing, `plugins/ISpawners/spawners.yml`
is copied (the old file is not changed). `/ispawners give` in SMP-Crates keeps working through the alias.

Tests: `./gradlew build` (unit tests + mocked listener tests); `tools/boot-test.sh` boots a throwaway server.

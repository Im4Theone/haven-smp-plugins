# Haven SMP plugins

The plugins written for **Haven SMP** (Paper 1.21.11, Java 21): economy, shop, orders, auction house, spawners, kits, crates, bounties, anti-cheat honeypots, punishments, register/login with
Mojang verification of bought accounts, and more. Everything here is original code by im4theone; third-party plugins we run next to them (LuckPerms, Vault, Multiverse, FastAsyncWorldEdit,
VoidGen) are not part of this repository.

## The plugins
| Folder | Plugin | What it does |
|---|---|---|
| [SMPAuction](SMPAuction) | SMP-Auction | Auction house with Buy It Now, bidding, a collection box and history. |
| [SMPAuth](SMPAuth) | SMP-Auth | Register and login for players without a bought account; bought accounts are verified with Mojang and walk in. |
| [SMPBounty](SMPBounty) | SMP-Bounty | Put money on a player's head; the killer collects it. |
| [SMPCatch](SMPCatch) | SMP-Catch | X-ray honeypots, mining statistics, staff vanish and freeze. |
| [SMPCore](SMPCore) | SMP-Core | Foundation plugin for the SMP - shared API, service registry and configuration. |
| [SMPCrates](SMPCrates) | SMP-Crates | Crates - a free one for everybody every hour, crates from the shop, menu, preview and opening animation. |
| [SMPDaily](SMPDaily) | SMP-Daily | A daily reward with a 7-day streak. |
| [SMPDrill](SMPDrill) | SMP-Drill | The Amethyst Drill - a maxed netherite pickaxe that mines a 5x5 tunnel with every swing. |
| [SMPEconomy](SMPEconomy) | SMP-Economy | Economy for the SMP - UUID-keyed balances in SQLite, /balance, /pay, /eco and a public service API. |
| [SMPInfo](SMPInfo) | SMP-Info | Join and quit messages, server list text, announcer and info commands. |
| [SMPKits](SMPKits) | SMP-Kits | One kit per rank (the Founder plays too). The admin rank gets no kits. |
| [SMPMarket](SMPMarket) | SMP-Market | Player auction house for the SMP - command-driven listings with crash-recoverable purchases and item delivery. |
| [SMPOrders](SMPOrders) | SMP-Orders | A buy-order marketplace with escrow. |
| [SMPProtect](SMPProtect) | SMP-Protect | Protects areas of the server with regions and flags (no building, no PvP, no mob spawning...). Hub first. |
| [SMPPunish](SMPPunish) | SMP-Punish | Custom kicks, bans, mutes and warnings with screens, presets and a record for every player. |
| [SMPRtp](SMPRtp) | SMP-RTP | Random teleport in the overworld, the nether and the end. |
| [SMPScoreboard](SMPScoreboard) | SMP-Scoreboard | Sidebar with money, shards, kills, deaths, streak, rank, playtime and ping, plus LuckPerms chat prefixes. |
| [SMPShop](SMPShop) | SMP-Shop | Items for money, keys and spawners for Shards. |
| [SMPSocial](SMPSocial) | SMP-Social | Private messages, /reply, /ignore, /trash and /afk. |
| [SMPSpawners](SMPSpawners) | SMP-Spawners | Stackable spawners that store loot and experience, with a menu to collect and sell. |
| [SMPStats](SMPStats) | SMP-Stats | Kills, deaths, playtime and more, with leaderboards. |
| [SMPTravel](SMPTravel) | SMP-Travel | Spawn and hub, homes, teleport requests and /back, with a stand-still warm-up. |
| [SMPVault](SMPVault) | SMP-VaultBridge | Registers SMP-Economy as the Vault economy so Vault-based plugins use SMP-Economy balances. |
| [SMPWorth](SMPWorth) | SMP-Worth | Adds "Worth - price" to the lore of every item, from a price sheet with a value per enchantment level. |

Each folder has its own README with the commands, permissions and configuration.

## Building
Every plugin is its own Gradle project (Java 21, Paper API 1.21.11). In a plugin folder run `./gradlew build` (Windows: `gradlew.bat build`); the jar appears in `build/libs`. The build runs the
unit tests and treats compiler warnings as errors.

Some plugins need jars in their own `libs` folder that are not in this repository; the file `libs-needed.txt` in the folder lists them:
- `VaultAPI-1.7.jar`: the Vault API (https://github.com/MilkBowl/VaultAPI).
- `luckperms-api-5.4.jar`: the LuckPerms API.
- `log4j-api-2.25.2.jar`, `log4j-core-2.25.2.jar`: from Maven Central (SMP-Auth).
- `SMP-Core-1.0.0.jar`, `SMP-Economy-1.0.0.jar`: build SMPCore, then SMPEconomy, and copy their jars into the `libs` folder of the plugins that list them.

Build order: SMPCore, SMPEconomy, then the rest.

## Other folders
- `LuckPerms-setup`: the ranks and permissions of the server (`haven.json.gz` for `lp import`, the readable command list, and the script that generates it).
- `tools/e2e`: end-to-end tests that join a throwaway server with scripted Minecraft clients (mineflayer), a pretend Mojang server for testing the bought-account check, and an RCON helper. Read the README there.
- `changes.md`: the running log of everything that changed and was tested.
- `copy-new-jars-to-server.bat`: copies built jars into a stopped server's `plugins` folder.

## License
No license has been chosen yet, so by default all rights are reserved: you may read the code, but not reuse it until the author adds a license.

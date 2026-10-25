# SMP-Crates

Crates for the server (Paper 1.21.11, Java 21): **everybody gets a free crate every hour**, more crates are **sold in the shard
shop**, and players open them from a menu with a short animation.

> A "key" here is a **virtual key kept on the player's account, never an item in the inventory**. Keys are opened from the menu with
> `/keys` (also `/crates`), and the plugin config calls the five types Common, Prime, Gold, Amethyst and Crimson Key.

## Install
Copy `build/libs/SMP-Crates-1.0.0.jar` into `plugins/` and restart. Optional but expected: Vault + SMP-VaultBridge (money
rewards go through your SMP-Economy), DonutShop (the shard shop that sells crates, and the shard rewards), ISpawners (the
spawner rewards). Data is in `plugins/SMP-Crates/data.yml`; if that file is unreadable the plugin disables itself instead of
letting the next save wipe everybody's crates.

## Keyall (the hourly crate)
A countdown on the server clock (UTC): when it reaches 00:00 **everybody online gets one `common` crate** (announced in chat), and it
starts again at 60:00. A new round starts on every full hour for all players at the same moment, and the timer does not restart when
the server does. `/crates timer` and the crates menu show it; SMP-Scoreboard shows it with `<keyall>` (the default config has the line
"Keyall: 59:12"; add that line to your own scoreboard config). Change the crate with `hourly.crate`, the length with
`hourly.interval-minutes`. With `hourly.catch-up: true` a player who was offline during a round also gets it when they join
(never more than one).

## /keyall by hand
`/keyall <crate> [amount]` gives every player who is online that crate (1 to 100 each) and announces it. It is locked: nobody has the
permission `smpcrates.keyall` by default, not even operators, so give it to yourself only (`lp user YourName permission set
smpcrates.keyall true`; note that a LuckPerms `*` wildcard also includes it). As a second lock list your name under
`keyall.allowed-players` in the config: then only those players (and the console) can use it even if somebody else gets the permission.

## Keys from the shop
The shard shop (`/shop`, then the shards category) sells the five keys (tripwire hook icons: Common, Prime, Gold, Amethyst and Crimson Key), at the prices of
`shard-shop.prices.*-key` in DonutShop's config. The shards are taken first and the crate is saved to the player; if SMP-Crates
does not confirm that, the shards are given back. A bought key is added to the player's key balance and **no item is given**; the chat message says so. Make sure the server runs the rebuilt `DonutShop-1.8-SMP.jar`: the older one hands out a plain tripwire hook item.

## Using crates
| Command | Does |
|---|---|
| `/crates` | the menu: left click a crate to open one, right click to see every reward with its chance |
| `/crates open <crate>`, `/crates preview <crate>` | the same by command |
| `/crates balance`, `/crates timer` | your crates, the time to the next free one |
| `/crates give <player> <crate> [amount]`, `take` | admin (`smpcrates.admin`); `give` is also what you would use from a command block |
| `/crates setblock <crate>` / `removeblock` | admin: the block you look at becomes a crate (right click opens, left click shows the rewards); it cannot be broken or blown up |
| `/crates reload` | admin: reload `config.yml` (an invalid file is rejected and the old settings stay) |

## Rewards
Everything is in `config.yml`: any number of crates, each with weighted rewards of four kinds: `item` (with a name and
enchantments), `money` (through Vault), `shards` (DonutShop) and `command` (run by the console, `%player%` is the winner, handy for
`ispawners give %player% blaze 1`). `broadcast: true` announces a win to the whole server. The shipped config has five crates with 10
rewards each; the prices of what they give are guesses, please look at them and adjust.

## Fair and crash-safe
- The crate is taken and the reward handed over in one step; the animation only shows an outcome that is already final.
  Closing the menu early or a crash during the animation cannot lose a crate or a reward.
- A crate is never taken if the reward cannot be delivered (no room in the inventory, no economy, no DonutShop for shards): the
  player is told and keeps the crate. If handing over fails after the crate was taken, the crate is given back.
- Every change is written to disk (temporary file, then replace) before it counts; a failed write undoes the change.

## Tested
Unit tests cover the clock, config validation, reward probabilities, the store (restart, failed saves, damaged file), the hourly
round (one per round, never twice, away players), and opening (success, full inventory, failed delivery, failed save). A throwaway
server loads it, accepts every item and enchantment name and reloads. The menus and the animation need a connected player and were
**not** tested in game; the shop purchase was compiled and the reflection signatures checked, but not clicked.

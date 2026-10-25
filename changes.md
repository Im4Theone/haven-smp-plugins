# Changes

All jars are in `C:\Users\Kaloyan K\Plugins` (this folder). Nothing in `server\plugins` was touched; copy what you want yourself.
Every project below builds with `-Xlint:all -Werror` and its unit tests pass. Throwaway Paper servers (never yours) were used for
the real-server tests.

## Jars

| Plugin | Jar | Status |
|---|---|---|
| SMP-Worth | `SMP-Worth-1.0.0.jar` | **new**: Worth lines from the price sheet |
| SMP-Drill | `SMP-Drill-1.0.0.jar` | **new**: the Amethyst Drill (5x5 tunnels) |
| SMP-Crates | `SMP-Crates-1.0.0.jar` | **new**: hourly crates, shop crates |
| DonutShop | `DonutShop-1.8-SMP.jar` | rebuilt: sells real crates |
| SMP-Travel | `SMP-Travel-1.0.0.jar` | **new**: /spawn /hub /sethome /home /tpa /back |
| SMP-Protect | `SMP-Protect-1.0.0.jar` | new earlier tonight, more features added |
| SMP-RTP | `SMP-RTP-1.0.0.jar` | new earlier tonight, more features added |
| HavenBuilder | `HavenBuilder-1.0.0.jar` | **undo added, new hub (v3) inside** |
| SMP-Scoreboard | `SMP-Scoreboard-1.0.0.jar` | polished |
| DonutAuctionHouse | `DonutAuctionHouse-2.0.0-SMP.jar` | unchanged since last night |

## Added tonight

### HavenBuilder: `/havenbuild undo`
- Right before every build it saves the blocks of the build area to `plugins/HavenBuilder/snapshots/` (newest 5 kept).
  `/havenbuild undo`, then `/havenbuild confirm`, puts them back: the hub disappears and what was there before returns.
- If the snapshot cannot be saved, the build does not start (nothing is changed).
- Restoring uses the same chunk handling and per-tick budget as the build; `/havenbuild cancel` stops it, run undo again to finish.
- Sign text and chest contents that existed before the build are not restored (only block types and states).
- Tested on a throwaway server: two marker blocks placed, hub built, undo run: 84,992 blocks put back, both markers returned, and
  1,162 sampled hub blocks were air again. Unit tests cover the file format (round trip, damaged and truncated files, keep 5).
- Old configs are replaced automatically (`config-version` 3); a copy is kept as `config.old-<time>.yml`.

### SMP-Protect
- **Greeting and farewell** per region: `/protect greeting <region> <MiniMessage text>` and `/protect farewell ...`
  (`none` removes). Shown above the hotbar (`messages.greeting-display`: actionbar, title or chat). Works when walking,
  teleporting and changing world. Saved in `regions.yml`; old files load unchanged.
- **Armor stands and item frames** are now protected by `entity-damage` (they were open under the hub preset before).
- **Hook for other plugins**: `isProtectedSpot(...)`, used by SMP-RTP.

### SMP-RTP
- Never teleports anyone **into a protected region** (`avoid-protected-regions: true`, needs SMP-Protect, uses reflection so
  neither plugin needs the other). Tested end to end: with a whole-world no-build region `/rtp scout` found nothing; removing the
  flag, it found a spot.
- **Cooldowns survive restarts** (`cooldowns.yml`, written atomically).
- **`/rtp player <name> [world]`** (admin): sends someone with no warm-up, cooldown or cost.
- Range is a 5000 x 5000 square and the warm-up is 5 s for everyone (from earlier).

### SMP-Travel (new plugin)
- `/spawn`, `/hub`, `/setspawn [hub]`, `/sethome [name]`, `/home [name]`, `/homes`, `/delhome <name>`, `/tpa`, `/tpahere`,
  `/tpaccept`, `/tpdeny`, `/tpcancel`, `/back`. **5 s stand-still warm-up for everyone** (moving or damage cancels).
- Homes per rank with `smptravel.homes.<number>` (default 1) or `smptravel.homes.unlimited`.
- New players start in the hub. `/home` and `/back` refuse if something solid now fills the saved spot.
- Data in `travel.yml`, written atomically; if it is unreadable the plugin disables itself instead of letting homes be overwritten.
- 11 unit tests; boots on a throwaway server and refuses to run with a broken `travel.yml`.
  **The teleports themselves (warm-up, /tpa) were not tested with a connected player.**

### SMP-Scoreboard
- An older `config.yml` without `tab:` and `welcome:` falls back to the shipped defaults (your edits are kept).

### LuckPerms
- `LuckPerms-setup/haven-new-plugins.txt`: lines to paste after `lp import haven`: homes per rank (2/3/5/7/10/unlimited), and
  the admin permissions for Travel, RTP, Protect and HavenBuilder on the right ranks.

## Amethyst Drill (new plugin SMP-Drill)

- A netherite pickaxe named "Amethyst Drill" (pink) with Efficiency V, Unbreaking III, Fortune III and Mending. Every block mined with it
  breaks the whole 5x5 wall around it, so walking forward digs a 5x5 tunnel; mining down digs a 5x5 shaft. Sneak to mine one block.
- The tunnel floor is the floor you stand on, so it never digs the ground away under you.
- Every extra block goes through a normal block-break check first, so SMP-Protect and other plugins can still refuse it. Chests, signs,
  spawners, liquids and bedrock are never broken.
- Give it with `/drill give <player>`. It is also a rare reward in the Amethyst and Crimson crates (announced to everyone).
- Size, depth, name, enchantments and "unbreakable" are in `plugins/SMP-Drill/config.yml`.
- Unit tests and a boot test pass; mining with a real player was not tested.

## Worth lines (new plugin SMP-Worth)

- Every item shows "Worth: <price>" at the bottom of its lore, from your price sheet (`donutsmp_custom_prices.json`): base value plus the
  bonus per enchantment level (a diamond pickaxe with Efficiency V, Unbreaking III, Fortune III and Mending = 213). Enchanted books, curses
  and Minecraft's own enchantment names are handled. Prices read like 1,250 or 1.5.
- The sheet misses 277 items of 1.21.11 (copper gear, shelves, pottery sherds, waxed copper, the totem of undying...). `fallbacks` in
  `plugins/SMP-Worth/config.yml` price them (slabs/stairs/walls like the sheet's blocks, waxed copper like plain copper, totem 120, spawn eggs
  0...). All of them have a price now; the numbers are my estimates, please read the list.
- The line is written into the real item (so it stays when dropped or put in chests, and items keep stacking). Player inventories are
  refreshed twice a second, chests and barrels when opened and closed, dropped items when picked up. Menus of other plugins (shop, crates,
  auction house) and creative-mode players are left alone. Other lore (spawner, drill) is kept.
- Edit `plugins/SMP-Worth/prices.json` and `/worth reload` to change prices. `/worth` shows what you hold, `/worth missing` lists unpriced items.
- Tested: unit tests and a throwaway server (`/worth selftest` checks lines, idempotence and stacking). Not tested with a real player.

## Shop keys are real keys, not items

- The five keys in the shard shop (Common, Prime, Gold, Amethyst, Crimson) show a tripwire hook and are called **Key**. Buying one adds a
  virtual key to your account: nothing is put in the inventory, and the chat message says "saved to your account, open with /keys".
  Open them with `/keys` (alias of `/crates`). The old DonutShop jar handed out a plain tripwire hook **item**, so the rebuilt
  `DonutShop-1.8-SMP.jar` and `SMP-Crates-1.0.0.jar` must both be on the server (delete `plugins\SMP-Crates\config.yml` if you already
  started the old one, to get the new names).
- The SMP-Crates menu, messages and `/keyall` now say "key" as well.

## Respawn in the hub (SMP-Travel)

- Everybody who dies now respawns in the hub world, even with a bed: the spot saved with `/setspawn hub`, otherwise `hub` at 0.5 100 0.5
  (the middle of the new plaza). `respawn.mode: bed` keeps beds, `vanilla` turns it off. Needs the new `SMP-Travel-1.0.0.jar`; an existing
  `config.yml` needs no change (the default is the hub). Not tested with a real death.

## Crates (new plugin SMP-Crates, plus the shop)

- **Keyall:** a countdown on the server clock (UTC). At 00:00 everybody who is online gets one `common` crate (announced in chat)
  and it starts again at 60:00; it does not restart with the server. Players who were offline do not get it
  (`hourly.catch-up: true` would give it to them when they join). The scoreboard shows it as a small line "Keyall: 59:12".
- **Crates in the shop:** the five key slots of the shard shop now sell real crates: Common, Prime, Gold, Amethyst (pink in the menus) and Crimson, the shop's own names, for the shard prices already in DonutShop's config. Shards are taken first and given back
  if the crate was not saved to the player. `DonutShop-1.8-SMP.jar` was rebuilt for this.
- **`/keyall <crate> [amount]`:** gives everybody who is online that crate right now (announced in chat). Only you can use it: the
  permission `smpcrates.keyall` is held by nobody by default, not even operators; give it to yourself with
  `lp user YourName permission set smpcrates.keyall true`, and optionally put your name in `keyall.allowed-players` in the config.
  Note: the founder group's `*` wildcard in `haven-commands.txt` includes it.
- **Opening:** `/crates` menu (left click opens, right click previews every reward with its chance), a short roulette
  animation, optional crate blocks (`/crates setblock <crate>`), `/crates give|take` for staff. 10 weighted rewards per crate
  (items with enchantments, money through Vault, shards, spawners through `ispawners give`), best ones are announced.
- **Safe:** the crate is taken and the reward given in one step (the animation is decoration), nothing is taken when the
  inventory is full, failures hand the crate back, data is written atomically and a damaged `data.yml` disables the plugin.
- **Scoreboard:** new placeholders `<keyall>` (the countdown), `<crates>` and a default line "Keyall: 59:12". Your own
  `plugins\SMP-Scoreboard\config.yml` keeps your lines, so add `- "<#FBBF24>🔑 <white>Keyall: <#FBBF24><keyall>"` to its `lines:` yourself.
- The reward values (money, items, spawner chances) are my guesses: please read `plugins/SMP-Crates/config.yml` and adjust.
- The menu, the animation and the shop click were not tested with a real player (no way to connect one here): unit tests cover
  the rules, a throwaway server loads everything together.

## New hub map (v3): "floating autumn town"

Made after your DonutSMP screenshots. Built entirely from `/fill` and `/setblock`, installed inside HavenBuilder as the new
default hub; the previous hub is still there (`source: bundled-v2`).
- A round floating island (radius about 61 to 66) with a rocky underside, a ringed circular plaza with a fountain pit, 12
  pedestals and 24 lamp posts, four large timber-framed buildings (3 to 4 floors, red, crimson and dark-blue tiled roofs,
  signs: HAVEN HALL, SHOP, AUCTION HOUSE, WARPS), four small houses (SPAWNERS, ORDERS, HOMES, RTP), a river with a wooden
  bridge and a pond, market stalls, autumn trees, more than 20 floating islands around it and a floating HAVEN logo above the plaza.
  No purple.
- About 29,600 commands (the old hub had 3,540). `01_clear` first clears the whole area, so rebuilding over the old hub is
  clean; `/havenbuild undo` still restores whatever was there before.
- HavenBuilder's default limit is now x/z +-100, y 48..168; old configs are replaced automatically (`config-version` 4).
  New option `world-border-size` (0 = off): set it to about 200 to put a border around the hub after a successful build.
- Generator and previews: the folder C:/HavenSMP/v3 (hub_v3.py, render.py, the pictures in preview, README).

## What you need to do

1. Copy the jars you want into `server\plugins` and restart (SMP-Travel and SMP-Protect are new files; the others replace the old).
2. SMP-Protect, in the hub: `/protect define hub world hub`, `/protect preset hub hub`, optionally
   `/protect greeting hub <gold>Welcome to Haven</gold>`.
3. SMP-Travel, standing where you want it: `/setspawn` (survival spawn) and `/setspawn hub` (inside the hub world).
4. Old configs of SMP-RTP and HavenBuilder are replaced automatically (copy kept); SMP-Travel and SMP-Protect have no old config.
5. Paste `LuckPerms-setup/haven-new-plugins.txt` into the console after `lp import haven`.
6. Still pending from before: remove DonutSpawners (jar and folder) so ISpawners stacks keep their count; delete the old
   `plugins\DonutShop\config.yml` and `plugins\ISpawners\config.yml` to get the new prices.

## Not changed on purpose
- ISpawners, DonutOrders, DonutShop and the Donut auction patch: no known bug; I did not touch working money code.
- Player-facing behaviour of SMP-Protect, SMP-RTP and SMP-Travel (blocked actions, warm-up, /tpa) was tested with mocks and console
  commands only. Please try them with a non-op account.

## Questions for you
- Totem of undying price is not on your price sheet (kept at 1250).

## Rewrite of downloaded plugins (author: Kaloyan)
The downloaded plugins are replaced by original code, not relabelled. Order: Spawners, Shop (+Shards), Orders, Auction.
- **SMP-Spawners 1.0.0 (done)**: replaces ISpawners. Same config keys, `spawners.yml` format and item tag, and the
  `/ispawners` alias, so data and SMP-Crates rewards carry over. 15 unit tests + 12 mocked listener tests + a boot test on a
  throwaway server (enables, 8 creatures validate, import message, commands). Menus were not tried with a real player.
  To switch: remove `ISpawners-1.0.1-1.21.11.jar`, add `SMP-Spawners-1.0.0.jar` (it imports the old spawners.yml itself).
- SMP-Crates softdepend now also lists SMP-Spawners and SMP-Shop.
- **SMP-Shop 1.0.0 (done)**: replaces DonutShop. Same shards.yml format (auto-copied), same /shop and /shards, `donutshop.admin`
  still works. Prices carried over into the new config. 13 unit tests + boot test (enables, imports 70 shards, 4 categories,
  5 keys, 8 spawners). SMP-Scoreboard and SMP-Crates now look for SMP-Shop first. The clicks were not tried with a real player.
  To switch: remove DonutShop-1.8-SMP.jar, add SMP-Shop-1.0.0.jar (and SMP-Spawners). The Zombified Piglin spawner is gone.
- **SMP-Orders 1.0.0 (done)**: replaces DonutOrders. `/order`, `/orders`, buy orders with escrow, 5% creation tax, max 10 open
  orders, deliver / collect / cancel, enchantment picker, sort/filter/search, 7-day expiry with refund, offline refunds and
  payouts, retry of failed payouts. 24 unit tests (money rules, restarts, failed saves, unconfirmed payments) + boot test.
  The menus and chat prompts were not tried with a real player. DonutOrders kept its orders only in memory, so nothing is
  imported: open orders disappear when you switch (tell players, or switch when the list is empty).
- **SMP-Auction 1.0.0 (done)**: replaces DonutAuctionHouse. `/ah` (+ `/auction`), Buy It Now and bidding with anti-snipe, collection box,
  history, 5% sales tax (the old plugin had tax off; change `economy.sales-tax-percent` to 0 if you want it off), optional listing fee,
  limits by permission. 19 unit tests + boot test. Items are stored in full (Paper item bytes). The menus and chat prompts were
  not tried with a real player. The old database had one $10 test listing; not imported.
- Old downloaded jars moved from the Plugins folder to `_replaced-downloaded/` (nothing deleted; `server\plugins` untouched).
  LuckPerms nodes for the new plugins were appended to `LuckPerms-setup/haven-new-plugins.txt`.

### Switching the server over (when you are ready)
1. Stop the server. In `server\plugins` remove: DonutAuctionHouse-2.0.0-SMP.jar, DonutOrders-1.9-SMP.jar, DonutShop-1.8-SMP.jar,
   ISpawners-1.0.1-1.21.11.jar (and DonutSpawners if it is still there). Keep their folders as a backup.
2. Copy in: SMP-Auction, SMP-Orders, SMP-Shop, SMP-Spawners (1.0.0) and the rebuilt SMP-Crates and SMP-Scoreboard.
3. Start. Shards (70 for one player) and stacked spawners are imported by themselves. Open orders do not carry over.
4. Paste the new lines from `LuckPerms-setup/haven-new-plugins.txt`.

## SMP-Catch 1.0.0 (new): cheater catching, vanish, freeze
- Fake ore veins and sealed stashes with bait, hidden inside solid rock (`/trap vein`, `/trap stash`), violation points, alerts to staff,
  mining statistics (hidden rare ores), bait tracking, optional auto-seed in new chunks, `/v` vanish (hidden from players, tab list and the server
  list count; SMP-Scoreboard leaves vanished staff out of the online count and marks them), `/freeze`.
- 15 unit tests + a real-world throwaway test (3 veins and a stash placed in a generated world, checked block by block, removed).
- Not tried with real players: vanish, freeze, alerts, the click handling. Try `/v` with a second account first.
- SMP-Scoreboard was rebuilt for the vanish counts.

## SMP-Punish 1.0.0 (new): custom kicks, bans, mutes, warnings
- `/ban /tempban /ipban /unban /kick /mute /unmute /warn /history /punishment /banlist /alts`, configurable ban and kick screens, reason presets,
  temp times, silent `-s`, automatic steps for warnings, address bans, full record per player, saved atomically.
- 18 unit tests (rules, expiry, file, screens never take markup from typed text) + a console run on a throwaway server.
- Not tried with a connected player: the ban screen at login, kicks and chat blocking. Test with a second account.

## SMP-Kits 1.0.0 (new) and hidden staff commands
- **SMP-Kits**: one kit per rank (Settler, Keeper, Beacon, Lookout, Warden; 3d / 2d / 1d / 1d / 12h cooldowns, all editable), `/kit` menu with
  preview (right-click), `/kit <name>`, `/kitadmin give|reset|list|reload`. Guardian (admin) and Founder get no kits (`excluded-groups`, checked on the
  main rank and in the permissions). Nothing is claimed unless all items fit; the claim is saved before items are given. 8 unit tests + a boot test
  with LuckPerms and every other plugin. Assumption: "Admin" = the Guardian rank; Wanderer (default) gets no kit either. Add a kit with the
  id of any rank in config.yml to change that.
- **Hidden commands**: every staff command now has a `permission:` in plugin.yml (SMP-Punish, SMP-Catch, SMP-Orders /ordersadmin, SMP-Crates /keyall,
  SMP-Drill, SMP-Protect, SMP-Spawners, SMP-Travel /setspawn, HavenBuilder) and the Brigadier roots `/smpcore` and `/eco` use `requires(...)`.
  Players without the permission do not see these commands in their list or in tab-complete. Their tests were updated (the commands no longer
  even parse for players without a permission). Rebuilt: SMP-Core, SMP-Economy, SMP-Punish, SMP-Catch, SMP-Orders, SMP-Crates, SMP-Drill,
  SMP-Protect, SMP-Spawners, SMP-Travel, HavenBuilder, SMP-Kits.
- Permissions: new lines at the end of `LuckPerms-setup/haven-new-plugins.txt` (kits, cleanup of old plugin nodes) and a table in
  `LuckPerms-setup/permissions.md`.

## Permissions simplified (latest)
- `/kit` is visible to everybody (`smpkits.use` default true). Wanderers and ranks without a kit see the kits locked; Guardian and Founder are told their rank gets none.
- Every staff command (punishments, trap/vanish/freeze, kitadmin, ordersadmin, spawners, protect, drill, setspawn, havenbuilder, smpcore, eco ...) is for
  Guardian and Founder only: the permissions were taken away from Lookout and Warden in `LuckPerms-setup/haven-new-plugins.txt`, which also cleans up
  what an older version of the file had set. `LuckPerms-setup/permissions.md` is the table. SMP-Kits was rebuilt (only plugin.yml changed).
- **Kits update**: every kit now resets every 24 hours (config-version 2, so an older kits config is replaced automatically and kept as config.old-*.yml). The menu no
  longer says "N items": each kit shows its state and "Right-click to preview". Ranks get their own kit and every kit below it (through the LuckPerms
  inheritance in haven.json.gz): Beacon = Settler + Keeper + Beacon.
- **Void rescue (SMP-Travel)**: in the hub world, a survival player who falls below height 20 is teleported to the hub spot (`/setspawn hub`, else the configured
  respawn place) with fall damage cleared; void damage is cancelled there. Settings: `void-rescue` in SMP-Travel config.yml (missing section = on, hub world, y 20).
  Creative and spectator players are left alone. Logic unit tested; the teleport itself was not tried with a falling player.
- **Wanderer kit** (players without a rank): stone tools, iron armor, 16 bread, 16 torches, 16 logs, every 24 hours. All ranks inherit it, so a Beacon has Wanderer + Settler + Keeper +
  Beacon. Guardian and Founder still get none. Kits config-version 3 (replaced automatically). `haven.json.gz` and `haven-new-plugins.txt` were updated.
- **Kits buffed and Founder included**: every kit is much stronger (Wanderer: enchanted iron; Settler/Keeper: enchanted diamond; Beacon: max-enchanted diamond with mending, bow, 2 enchanted
  golden apples; Lookout: + elytra, rockets, totem; Warden: max-enchanted netherite set incl. hoe, elytra, crossbow, 8 enchanted golden apples, 2 totems). The Founder now gets all kits
  (excluded-groups is only `guardian`); the Founder's own kit permissions are set to true in `haven-new-plugins.txt` / `haven.json.gz`. Kits config-version 5.
- **Kits corrected**: netherite only in the Lookout and Warden kits; the Beacon kit is now max-enchanted diamond. No elytras and no firework rockets anywhere. Kits config-version 6.
- **Kits**: the netherite kits (Lookout, Warden) reload every 3 days; the others every 24 hours. Kits config-version 7.
- **/hub fix (SMP-Travel)**: without a spot saved by /setspawn hub, /hub (and a new player's first join) now goes to the hub place of the config (respawn: hub world 0.5 100 0.5) instead of the world's vanilla spawn point (which was y 65).

## /sell (SMP-Worth) and the 15% spread
- Shop prices and the Worth sheet were already identical (a shop item costs exactly its Worth). `/sell` now pays **85% of an item's Worth**, so selling is always 15% below buying
  (`sell.percent` in SMP-Worth config.yml). `/sell` opens a menu: put items in, check the total, press SELL (what cannot be sold comes back when you close); `/sell hand` sells the stack in
  your hand. Worn tools are paid in proportion to their durability; shulker boxes and bundles with contents, the Amethyst Drill, stash bait and spawners cannot be sold. Items are taken first
  and the money paid second (refused payment: items return; unconfirmed: logged, items not returned). Totem of undying: the sheet now says 1250 like the shop (it was 120).
- 5 new unit tests (SellMath). Not tried with a real player.

## Prices x10
- Every price multiplied by 10: all 1404 items of the sheet (diamond 250, iron ingot 120, cobblestone 15 ...), every enchantment bonus, the fallback rules (totem 12,500 like the shop), the shop's money prices,
  and the spawner sell prices (now 85% of the worth, like /sell). Shard prices are unchanged. Sell pays 85%: a diamond sells for 212.50.
- The old prices.json/config of SMP-Worth, the SMP-Spawners config and the SMP-Shop config are moved aside automatically (config.old-*.yml, prices.old-*.json) and replaced at the next start;
  shards.yml, spawners.yml, orders and the auction data are untouched. Start balance of SMP-Economy (100) was NOT changed; raise `balances.starting` if you want new players to afford things.
- **Starting balance 10,000**: SMP-Economy default `balances.starting` is now 10000.00 (new accounts only; the server's existing config must be edited by hand, then /eco reload).

## Polish and bug fixes (review pass)
- **Wrong-entry clicks**: the order lists (SMP-Orders: browse, your orders) and the auction lists (browse, your listings, shift-click cancel) now remember which entry was in which slot when the menu was drawn.
  Before, the list was recomputed when you clicked, so if another player added or removed an entry in between you could click the wrong one (for the auction: cancel the wrong listing).
- **SMP-Travel void rescue** read config.yml from disk on every player movement; it is now read once at start.
- All 16 plugins + HavenBuilder rebuilt, every unit test passes, and all 20 plugins together (with LuckPerms) start on a throwaway server without a warning or error.

## Overnight additions (early-DonutSMP feel)
- **/rtp menu** (SMP-RTP): `/rtp` now opens a menu with Overworld / Nether / The End and the number of players in each (`/rtp <world>` still works).
- **SMP-Info 1.0.0 (new)**: `[+]`/`[-]` join and quit messages with the rank prefix, welcome broadcast for new players, server list MOTD, announcer (tips every 5 min),
  `/discord /store /vote /rules /commands` (edit the text and links in its config.yml; put your real Discord invite there!).
- **SMP-Stats 1.0.0 (new)**: kills, deaths, mobs, blocks, playtime; `/leaderboard` (/lb, /top) menu with top-10 heads, `/baltop /killtop /playtop ...`, `/stats [player]`.
- **SMP-Daily 1.0.0 (new)**: `/daily` 7-day streak calendar with money, shards and crates; join reminder.
- **SMP-Bounty 1.0.0 (new)**: `/bounty set|list|check`, escrowed bounties paid to the killer.
- **UI**: menu titles of the shop, orders, auction, kits and daily menus are in the small-caps style (configs bumped, old ones kept as config.old-*.yml).
- New unit tests for all new plugins; `copy-new-jars-to-server.bat` now copies them too. LuckPerms: only `smpinfo.admin` was added (guardian); everything else is public.
- **SMP-Social 1.0.0 (new)**: `/msg /reply /ignore /afk /trash`. Away players (auto after 5 min idle, or `/afk`) get no shards from SMP-Shop and no playing time in SMP-Stats.
  Staff cannot be ignored (`smpsocial.unignorable`, guardian). Replaces the vanilla /msg and /tell (they now respect /ignore and vanish).
- `/trash` (bin) added to SMP-Social. All 21 plugins + HavenBuilder rebuilt, every unit test passes, and all 25 plugins together with LuckPerms start without a warning or error.
  `haven.json.gz` was regenerated and imports cleanly.

### To put the overnight work on the server
1. Stop the server. Run `copy-new-jars-to-server.bat` (copies every jar, including the new SMP-Info, SMP-Stats, SMP-Daily, SMP-Bounty and SMP-Social).
2. Start it, then `lp export backup` and import the new `haven.json.gz` (or paste `haven-new-plugins.txt`).
3. In `plugins/SMP-Info/config.yml` put your real Discord / store / vote links. In `plugins/SMP-Economy/config.yml` set `balances.starting: "10000.00"` and `/eco reload`.
4. Join with a normal account and try: /rtp, /daily, /leaderboard, /kit, /sell, /shop, /bounty, /afk, /trash, /msg.

- **Social spy removed** (on purpose: private messages stay private). SMP-Social has no way to read other people's messages; the `smpsocial.spy` permission no longer exists.

## SMP-Auth 1.0.0 (new): register / login for players without a bought account
- `/register /login /changepassword /logout /unregister` and `/auth` (staff). Hashed passwords (PBKDF2), brute-force lock, frozen and blinded until logged in, passwords kept out of the log,
  10 minute same-address session, 3 accounts per address, exact capitals, protected premium names. Bought accounts verified by FastLogin (version 4 UUID) walk in; `/auth uuid <player>` shows which kind a player has.
- Needs `online-mode=false` and `enforce-secure-profile=false`. 12 unit tests + an end-to-end test with real game clients (18 checks) all pass. FastLogin itself could not be tried (no Mojang account here).

## Tested with real game clients (bots)
I can now join the throwaway server with scripted Minecraft clients (`tools/e2e`). 44 of 44 checks pass against all the plugins together with LuckPerms and the real `haven.json.gz`:
the server list text, join messages, **a normal player sees none of the staff commands (and all public ones), an operator sees them**, /shop (money and shards, buying), /kit (claim, cooldown), /sell hand and
the sell menu, /daily, /leaderboard, the /rtp menu, /order windows, an auction sale between two players (list, buy, collect, seller told), /bounty, /msg and /reply, /afk, /trash, /rules, /stats, /trap for an
operator but not for a normal player. The server log of that run has no error or exception. SMP-Auth: 18 of 18 checks (register, login, logout, wrong passwords, kick, lock, session, capitals) and no password in the log.
Not covered by bots: FastLogin (needs a Mojang account), vanish / freeze / ban screens, PvP bounty kills, rtp teleports, spawner stacking.

## SMP-Auth: our own check of bought accounts (replaces FastLogin)
FastLogin needs ProtocolLib, and ProtocolLib 5.4.0 broke logins on 1.21.11 ("Server is not fully started yet. Please retry" long after startup). SMP-Auth now verifies bought
accounts itself, so **FastLogin and ProtocolLib can be deleted**:
- A small hook on the login of each connection (`LoginHook`). For a name that has to be proven it sends the same encryption request an online-mode server sends, checks the
  client's answer with Mojang's session server (`Mojang`), and lets the login go on with the real UUID, name capitals and skin. Everything else passes untouched.
- `Gate` decides who has to prove it: names marked with `/premium` (or `/auth premium <name>`), and unknown names that a bought account has (`premium.auto-detect`). A name with a password
  account here logs in with the password. Lookups are limited per address and remembered for a while; if Mojang is down a new name waits (no guessing).
- Safe failure: if the server's internals change, the hook reports it in the log and switches off (everybody registers and logs in). `config-version` 2 (old config kept).
- Tested with real game clients against a pretend Mojang (`tools/e2e/fakemojang.js`, `premiumbot.js`): 11/11 with auto-detect and 9/9 without it (the 9th was a wrong test wording;
  checked by hand): a bought account walks in with its Mojang UUID, nobody can use a bought name without owning it, the owner still gets in, `/premium` works, `/premium` is refused
  for a name no bought account has. The hash of the exchange is also checked against Mojang's published examples. Not tried: the real Mojang servers (needs a real account),
  so your first real join with a bought account is the final test; send me the log if it does not work.

## /ranks: where players buy ranks (SMP-Info 1.0.0, config-version 2)
- Ranks are sold on your **web store** (real money). `/ranks` (also `/rank`, `/buyrank`) opens a menu with one icon per rank (Settler, Keeper, Beacon, Lookout, Warden): price and
  perks, a glowing "You have this rank" for ranks the player owns (checked through LuckPerms `group.<rank>`), and an Open-the-store button. A click sends the store link in chat.
- In `plugins/SMP-Info/config.yml` set `ranks.store-url`, the prices and perk texts. **In your store, make the package run `lp user {name} parent set <rank>` after payment**
  (Tebex: package commands). The server never handles money for this.
- Also fixed: the join message no longer crashes when Vault is missing. Tested with a real client (8/8: `tools/e2e/rankbot.js`) and 6 unit tests; no errors in the server log.

## Prices in euro (fair, one-time, no subscriptions)
- Ranks (each includes the ones below): **Settler €2.99, Keeper €5.99, Beacon €9.99, Lookout €14.99, Warden €24.99** (about €58 for everything, nobody has to buy them one by one).
- Crate keys: **Prime €0.99, Gold €1.99, Amethyst €3.99, Crimson €6.99** each, packs of 5 about 20% cheaper (€3.99 / €7.99 / €15.99 / €27.99). The free hourly Common key stays free.
- Shown in `/ranks` and `/store` (SMP-Info config). Set the same prices in your web store; delivery commands: `lp user {name} parent set <rank>` and `crates give {name} <crate> <amount>`.
- Everything sold can also be earned in game, so nobody is forced to pay.

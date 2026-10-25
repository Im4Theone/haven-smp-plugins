# SMP-Market

Player auction house for a custom survival SMP. Version 1 is command-driven (no inventory GUI): list the item in
your hand, browse, buy, cancel, collect, history, and permission-protected admin tools. Money moves through
SMP-Economy's `EconomyService`; items and listings are stored in SQLite and survive crashes in a recoverable way.

| | |
|---|---|
| Plugin name | `SMP-Market` |
| Version | `1.0.0` |
| Main class | `net.kaloyan.smpmarket.SMPMarket` |
| Server | Paper **1.21.x** (`paper-api 1.21.11-R0.1-SNAPSHOT`, `api-version: 1.21.11`), same as SMP-Core / SMP-Economy |
| Java | **21** |
| Required plugins | `SMP-Core` 1.0.0, `SMP-Economy` 1.0.0 (`depend: [SMP-Core, SMP-Economy]`) |
| Folia | Not supported |

SMP-Market exposes **no public API** in v1, so there is no API example to compile.
**Read [docs/TRANSACTION_DESIGN.md](docs/TRANSACTION_DESIGN.md)** for the purchase workflow, crash recovery and the
exact limits of what is guaranteed.

## Build

```
./gradlew clean build        # gradlew.bat on Windows cmd/PowerShell
```

Output: `build/libs/SMP-Market-1.0.0.jar`. Compiles with `-Xlint:all -Werror`. SMP-Core and SMP-Economy are
compile-only dependencies taken from `libs/SMP-Core-1.0.0.jar` and `libs/SMP-Economy-1.0.0.jar` (copies of their
built jars; replace them if you rebuild those plugins). They are never packaged.

## Install

1. Stop the server (do not use `/reload`).
2. Put `SMP-Core-1.0.0.jar`, `SMP-Economy-1.0.0.jar` and `SMP-Market-1.0.0.jar` in `plugins/`.
3. Start. `plugins/SMP-Market/config.yml` and `plugins/SMP-Market/market.db` are created.

The SQLite JDBC driver is **not** bundled: Paper 1.21.11 ships `sqlite-jdbc 3.49.1.0` (same choice as SMP-Economy).
The jar contains only SMP-Market's own classes, `plugin.yml` and `config.yml`. Startup checks that the required
classes exist and that SMP-Economy published its service; otherwise SMP-Market disables itself with a clear message.
An invalid `config.yml` at startup lists every problem and disables SMP-Market.

## Commands

`/market` (alias `/ah`). Prices are plain numbers with at most 2 decimals (`25`, `12.50`); no signs, exponents or
grouping. Currency symbol and formatting come from SMP-Economy.

| Command | Permission (default) | What it does |
|---|---|---|
| `/market`, `/market help` | `smpmarket.use` (everyone) | Help and number of active listings |
| `/market sell <price>` | `smpmarket.sell` (everyone) | Lists the stack in your main hand for a **total** price |
| `/market listings` | `smpmarket.use` | Your open listings: id, item, amount, price, time left |
| `/market browse [page]` | `smpmarket.use` | Active listings, newest first, 8 per page |
| `/market buy <id>` | `smpmarket.buy` (everyone) | Buys a listing |
| `/market cancel <id>` | `smpmarket.cancel` (everyone) | Cancels **your own** active listing; the item returns to you |
| `/market collect` | `smpmarket.collect` (everyone) | Collects items waiting for you |
| `/market history [page]` | `smpmarket.use` | Your last 100 completed sales/purchases |
| `/market admin` | any admin permission | Admin help |
| `/market admin listings [page]`, `listing <id>`, `tx <id>`, `issues` | `smpmarket.admin.inspect` (op) | Inspect any listing / transaction; list everything needing a decision |
| `/market admin cancel <id> <reason>` | `smpmarket.admin.cancel` (op) | Cancels anyone's active listing; the item goes to its **owner**; audited |
| `/market admin recover` | `smpmarket.admin.recover` (op) | Runs the sweep now (expire, fee retry, deliver to online owners) |
| `/market admin resolve payment <tx> <paid\|unpaid> <reason>` | `smpmarket.admin.recover` | Decides an uncertain payment |
| `/market admin resolve delivery <id> <delivered\|retry> <reason>` | `smpmarket.admin.recover` | Decides an uncertain delivery |
| `/market admin resolve escrow <listing> <activate\|return\|abort> <reason>` | `smpmarket.admin.recover` | Decides an uncertain listing |
| `/market admin resolve fee <tx> <collected\|retry> <reason>` | `smpmarket.admin.recover` | Decides an uncertain fee |
| `/market admin reload` | `smpmarket.admin.reload` (op) | Reloads `config.yml` (all-or-nothing) |

`smpmarket.admin` grants all four admin permissions. There is deliberately no command that deletes records or
creates an item. Every `resolve` and admin cancel needs a reason (min. 5 characters), writes a row to the
`admin_actions` table and is logged with actor, listing, transaction, delivery and reason.

## Configuration

`plugins/SMP-Market/config.yml` (identical to [`src/main/resources/config.yml`](src/main/resources/config.yml)):
`listing.max-per-player` (10), `listing.duration-hours` (72), `listing.min-price` / `max-price` (`0.01` /
`1000000.00`), `listing.min-amount` / `max-amount` (1 / 64), `fee.percent` (`0`), `purchase.allow-self-purchase`
(false), `restrictions.blocked-materials` (empty; Material names with `*` wildcards, e.g. `*_SHULKER_BOX`, every entry
must match a real material), `logging.debug` (false). Amounts are quoted strings. The whole file is validated on
startup and on `/market admin reload`; a rejected reload keeps the previous settings. The effective maximum price is
also capped by SMP-Economy's `transactions.maximum-amount`. There is intentionally no currency setting.
Expiry-sweep interval (60 s) and delivery batch size are fixed constants, not configuration.

## Listing, fee and expiry rules

* A listing is the exact stack (all data components, enchantments, name, lore, persistent data, nested contents),
  stored via `ItemStack#serializeAsBytes()`; it is removed from your inventory only after a copy is durable.
* Limits are checked in the same database transaction that records the listing, so they cannot be raced.
* Expiry uses the stored UTC `expires_at`; a sweep runs every minute and at startup, and an expired but unswept
  listing cannot be bought. Expired/cancelled items are delivered if the owner is online with room, otherwise they wait
  for `/market collect`.
* Fee: percent of the price, rounded **down** to the cent. The seller is paid the full price by an atomic transfer, then
  the fee is withdrawn from the seller (so the seller nets price − fee). The fee is destroyed. See the design document
  for why it is a separate step and what happens if it fails. Buying your own listing (if allowed) moves no money and
  has no fee.

## Item persistence and delivery

Items owed to a player are rows in `deliveries` (one per listing, enforced by a unique index). A delivery is claimed,
given on the main thread all-or-nothing (the whole stack must fit), then marked delivered; a refused item returns to
pending. Nothing is ever dropped on the ground. A crash mid-delivery makes the row `UNCERTAIN`: it is never redelivered
automatically; an admin decides.

## Transaction workflow and recovery (summary)

Reserve listing + record intent (SQLite) → one atomic `EconomyService.transfer` → record the outcome and create the
buyer's delivery in one transaction → fee → deliver. No payment is reported before Economy confirms it. Definite
failures reopen the listing; an unprovable outcome becomes `PAYMENT_UNCERTAIN` and is **never retried or refunded
automatically**. SMP-Economy has no idempotency key or transaction lookup, so such cases need an admin; the design
document proposes the minimal API addition. At startup, interrupted states are only *moved* to uncertain states.

## Database, backups, multi-server

`plugins/SMP-Market/market.db` (SQLite, WAL, `synchronous=FULL`, 5 s busy timeout, schema version in
`PRAGMA user_version`, currently 1; a newer database is refused). Back up with the server **stopped**, copying
`market.db`, `market.db-wal` and `market.db-shm` together (or use `sqlite3 market.db ".backup file"`). Restoring an old
backup can roll back sales that already moved money in SMP-Economy; back up `economy.db` at the same moment.
**Do not point several servers at one database file**; shared multi-server operation is unsupported and untested.

## Tests

`./gradlew test` — 78 tests, all on temporary databases:

* **Unit tests** (real SQLite, fake Economy and inventory): listing, purchase, fee, recovery, store constraints,
  configuration.
* **Mocked integration tests**: the real Brigadier `/market` tree with mocked Bukkit senders (`MarketCommandsTest`),
  and two real named threads proving database work stays off the main thread (`ThreadDisciplineTest`).
* **Real Paper server tests: none are automated.** `BukkitItemGateway` (real `ItemStack` serialization, inventory
  removal/insertion, room checks), the Brigadier registration, and anything involving a connected player require the
  manual smoke tests below.

## Manual Paper server smoke tests

Use a throwaway copy of the server, never the production world. With SMP-Core, SMP-Economy, SMP-Market and one player:

1. Start; confirm "SMP-Market … enabled" and no errors.
2. Hold a named, enchanted item with lore: `/market sell 10` → item leaves the hand; `/market listings` shows it.
3. With a second player: `/market browse`, `/market buy <id>` → balances change by the price, the item arrives
   identical (name, lore, enchantments).
4. Fill the buyer's inventory, buy another listing → "make room"; free a slot, `/market collect` → item arrives once;
   repeat `/market collect` → nothing.
5. `/market cancel <id>` (owner) returns the item; another player gets "not yours".
6. Set `listing.duration-hours: 1`, reload, list, advance/wait (or edit `expires_at` in a copy of the db): item returns.
7. Kill the server process (not `stop`) right after `/market buy`; restart; check `/market admin issues`.
8. `/market admin reload` with a broken config → rejected, old settings kept.
9. Without op: every `/market admin …` command is refused.

## Known limitations

* No distributed atomicity between SQLite, SMP-Economy and the Minecraft player file (see the design document §1, §7).
* An Economy call interrupted by a crash cannot be verified (no idempotency key / lookup in SMP-Economy 1.0.0): admin decision.
* Escrow of an item at the moment of a crash depends on whether Minecraft saved the player file: admin decision.
* The fee is a separate withdrawal and can lag behind the sale if it fails.
* Items that cannot be restored by a future server version stay pending (logged) rather than being dropped.
* No GUI, no partial-stack purchases, no auctions/bids, no cross-server market.

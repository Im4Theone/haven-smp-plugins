# SMP-Economy

Economy plugin for a custom survival SMP. Version 1 provides UUID-keyed balances stored in SQLite,
`/balance`, `/pay`, the `/eco` admin commands, automatic accounts for new players, and a public service
API that other plugins use through SMP-Core's service registry.

| | |
|---|---|
| Plugin name | `SMP-Economy` |
| Version | `1.0.0` |
| Main class | `net.kaloyan.smpeconomy.SMPEconomy` |
| Server | Paper **1.21.x** (built against `paper-api 1.21.11-R0.1-SNAPSHOT`, `api-version: 1.21.11`) |
| Java | **21** |
| Required plugin | `SMP-Core` 1.0.0 (`depend: [SMP-Core]`) |
| Folia | Not supported (the plugin does not declare `folia-supported`) |

The Paper/Java/Gradle versions are the ones SMP-Core uses (Paper API `1.21.11-R0.1-SNAPSHOT`, Java 21,
Gradle 9.8.2 via the wrapper).

## Build

Requires a JDK 21. The Gradle wrapper (9.8.2, checksum-pinned, copied from SMP-Core) is included.

```
gradlew.bat build        # Windows cmd / PowerShell
./gradlew build          # Linux/macOS/Git Bash
```

Output: `build/libs/SMP-Economy-1.0.0.jar`. Run only the tests with `gradlew test`. The build compiles with
`-Xlint:all -Werror`, runs all tests, and also compiles the example plugin in `src/example` (see
[Economy API](#economy-api)).

SMP-Core is a compile-only dependency taken from `libs/SMP-Core-1.0.0.jar` (the jar built by the SMP-Core
project; replace it if you rebuild SMP-Core). It is not packaged into the Economy jar.

## Install

Dependencies at runtime:

1. **SMP-Core 1.0.0** in `plugins/` (required; SMP-Economy will not load without it).
2. **The SQLite JDBC driver.** It is *not* bundled in the SMP-Economy jar: Paper 1.21.11 ships
   `org.xerial:sqlite-jdbc:3.49.1.0` on the server class path, so bundling a second copy would add 14 MB for
   nothing (and relocating it would break its native library). The plugin checks for the driver at startup and
   refuses to start with a clear message if it is missing (for example on a server that is not Paper).
   The final jar therefore contains only SMP-Economy's own classes, `plugin.yml` and `config.yml`.

Steps:

1. Stop the server (do not use `/reload`).
2. Copy `SMP-Core-1.0.0.jar` and `SMP-Economy-1.0.0.jar` into `plugins/`.
3. Start the server. `plugins/SMP-Economy/config.yml` and `plugins/SMP-Economy/economy.db` are created.

Players who joined before SMP-Economy was installed get their account (and the starting balance) the next
time they join.

## Configuration

`plugins/SMP-Economy/config.yml` (identical to the bundled default,
[`src/main/resources/config.yml`](src/main/resources/config.yml)):

```yaml
currency:
  symbol: "$"                       # plain text, 1-8 characters

balances:
  starting: "100.00"                # given to a new account once
  maximum: "1000000000000.00"       # highest balance a credit may reach

transactions:
  maximum-amount: "1000000000.00"   # largest single /pay, deposit or withdrawal

logging:
  debug: false                      # also log every transaction (INFO, [DEBUG] marker)
```

- **Write amounts as quoted text.** YAML reads unquoted decimals as binary floating point. Integers and short
  unquoted decimals (below 10<sup>12</sup>) are accepted and read exactly; larger unquoted decimals are rejected.
- Amounts must have at most two decimals, be above the minimum shown below, and be at most
  `1,000,000,000,000,000.00`. `starting` may be `0`; `starting` must not exceed `maximum`.
- Missing or empty keys use the defaults above; the file is never rewritten. Unknown keys are ignored
  without a warning.
- There are no database settings: the file name (`economy.db`) and the 5 s busy timeout are fixed.
- **Validation is all-or-nothing.** At startup an invalid file stops SMP-Economy from enabling (every problem
  is listed in the console, SMP-Core is unaffected). `/eco reload` validates the complete file first; if
  anything is invalid the whole reload is rejected and the previously active settings stay in effect.
- Reload changes apply to the next operation. A lowered `balances.maximum` never reduces existing balances;
  such an account just cannot receive more until it is below the new maximum. `balances.starting` only affects
  accounts created afterwards.

| Key | Minimum | Maximum |
|---|---|---|
| `balances.starting` | `0.00` | `balances.maximum` |
| `balances.maximum` | `0.01` | `1,000,000,000,000,000.00` |
| `transactions.maximum-amount` | `0.01` | `1,000,000,000,000,000.00` |

## Commands and permissions

| Command | Permission | Description |
|---|---|---|
| `/balance` (alias `/bal`) | `smpeconomy.balance` | Your own balance |
| `/balance <player>` | `smpeconomy.balance.others` | Another player's balance (looking yourself up by name needs no extra permission) |
| `/pay <player> <amount>` | `smpeconomy.pay` | Pay a known player |
| `/eco balance <player>` | `smpeconomy.admin.balance` | Balance and UUID of any account |
| `/eco set <player> <amount>` | `smpeconomy.admin.set` | Overwrite a balance (0 to the maximum balance) |
| `/eco add <player> <amount>` | `smpeconomy.admin.add` | Create money |
| `/eco take <player> <amount>` | `smpeconomy.admin.take` | Remove money (never below zero) |
| `/eco reload` | `smpeconomy.admin.reload` | Re-read `config.yml` (all-or-nothing) |

| Permission | Default | Meaning |
|---|---|---|
| `smpeconomy.balance` | everyone | View own balance |
| `smpeconomy.balance.others` | op | View others' balances |
| `smpeconomy.pay` | everyone | Use `/pay` |
| `smpeconomy.admin.balance` / `.set` / `.add` / `.take` / `.reload` | op | One administrative subcommand each |
| `smpeconomy.admin` | op | Parent node: grants every `smpeconomy.admin.*` node and `smpeconomy.balance.others` |

Behaviour notes:

- `<player>` may be an online name, the last known name of a player with an account (any case), or a UUID.
  Looking up or acting on an unknown name **never creates an account**; accounts are created only when a player
  joins.
- `/pay` refuses self-payments, unknown recipients, zero/negative/malformed amounts, more than two decimals,
  amounts above the transaction maximum, insufficient funds, and a recipient whose balance would exceed the
  maximum. The payer sees success only after the database transaction has committed; the recipient is notified
  if online. While a player's previous `/pay` is still being processed, a second one from the same player is
  refused ("still being processed"), so a repeated or spammed command cannot pay twice.
- `/eco add`, `take` and `set` use the same validation and the same transaction code as player operations. They
  are exempt from `transactions.maximum-amount` (not from the maximum balance, and balances still never go
  negative). Every successful change is logged to the console as
  `[ECO] <actor> (<actor-uuid>) added/took/set ... <target> (<target-uuid>) ...`; failed attempts are not logged as
  changes. The sender is told the target name and UUID, the amount and the new balance, and an online target is told
  an administrator changed their balance.
- Console senders can use `/eco ...` and `/balance <player>`; `/pay` is players only.
- Permissions are checked inside each command, so a sender without access gets a message naming the missing node.
- Commands are registered through Paper's Brigadier API, not `plugin.yml`. Tab completion suggests online
  player names (only if the sender has the relevant permission; `/pay` omits yourself).
- Messages use SMP-Core's chat prefix (`prefix` in SMP-Core's `config.yml`).

## Money precision and limits

- Currency has exactly **two decimal places**. All calculations use `BigDecimal`; `double`/`float` are never
  used. Amounts that would need rounding (e.g. `1.005`) are rejected, never rounded.
- Persisted balances and amounts are 64-bit integers of hundredths.
- **Absolute maximum for any amount or balance: `1,000,000,000,000,000.00`** (10<sup>17</sup> hundredths, about
  1/92 of `Long.MAX_VALUE`). This leaves headroom so the sum of any two valid values cannot overflow; additions
  still use `Math.addExact`, and the database has a `CHECK (balance >= 0 AND balance <= 10^17)` as a last line
  of defence.
- Configurable ceilings below that: default maximum balance `1,000,000,000,000.00`, default maximum
  transaction `1,000,000,000.00`.
- Accepted `/pay` and `/eco` input: optional leading `-` (reported as "cannot be negative"), digits, optionally
  `.` and digits. Rejected: plus sign, exponents (`1e3`), thousands separators, whitespace, `NaN`, non-ASCII
  digits, anything over 40 characters.
- A player's **UUID is their permanent identity**. The last known name is stored only as optional metadata for
  lookups and display. A new player gets a name that no other account keeps (a different account still carrying
  that name loses it). The starting balance is granted exactly once per UUID, whatever the name does.

## Economy API

Other plugins use `net.kaloyan.smpeconomy.api.EconomyService`, obtained from SMP-Core's service registry.
Full documentation: **[docs/API_USAGE.md](docs/API_USAGE.md)**. A compile-tested example plugin is in
[`src/example/java/net/kaloyan/example`](src/example/java/net/kaloyan/example); the build compiles it and
`ExamplePaymentsTest` runs it against a real database.

```java
EconomyService economy = core.getServices().get(EconomyService.class);
economy.transfer(from, to, new BigDecimal("12.50"))
       .thenAccept(result -> { /* result.status() is SUCCESS or an explicit reason */ });
```

The API offers balance queries, `hasFunds`, `deposit`, `withdraw`, `transfer` and currency formatting. It
deliberately has no method to create accounts or overwrite balances. All calls return `CompletableFuture`s.

## Database, threading and transaction guarantees

- **Location:** `plugins/SMP-Economy/economy.db` (SQLite, WAL mode, so `economy.db-wal` and `economy.db-shm`
  appear next to it while the server runs). One table, `accounts(uuid PRIMARY KEY, balance, last_name,
  created_at, updated_at)`.
- **Schema versioning:** stored in `PRAGMA user_version` (currently 1). Migrations run one step at a time, each in
  its own transaction together with the version bump. A database with a *newer* version than the plugin
  understands is refused and left untouched.
- **Threading:** all database work runs on one dedicated thread (`SMP-Economy-Storage`), never on the main
  thread. Commands and the join listener only enqueue work; results are sent to players back on the main thread.
  Operations execute in submission order.
- **Atomicity:** every operation that changes money is one `BEGIN IMMEDIATE ... COMMIT` transaction. A transfer
  debits and credits inside the same transaction; if anything fails in between (verified by an injected database
  fault in the tests) it rolls back and neither balance changes. `IMMEDIATE` takes SQLite's write lock before the
  balance is read, so concurrent payments, even over separate database connections, cannot overspend (the
  concurrency tests fail if `IMMEDIATE` is replaced by a deferred `BEGIN`).
- **No false success:** a result is `SUCCESS` only after `COMMIT` returned normally. Database errors yield
  `STORAGE_ERROR` (and a `SEVERE` log entry), never a success.
- **Durability:** `synchronous=FULL`; a committed payment survives a power cut. `busy_timeout` is 5 s.
- **Shutdown:** on disable, queued operations are completed (up to 30 s) before the connection is closed.
  Payments that commit while the server is shutting down are not reported to the players.

### Backups

Copy the database while the server is **stopped** (copy `economy.db` and, if present, `economy.db-wal`
and `economy.db-shm` together), or take a consistent online copy with
`sqlite3 plugins/SMP-Economy/economy.db ".backup 'economy-backup.db'"`. To restore, stop the server, replace
`economy.db`, and delete any leftover `economy.db-wal` / `economy.db-shm` from the old database. Keep backups
before updating the plugin.

## Testing

`gradlew test` runs 185 automated tests. **They are unit and integration tests, not server tests.**

| Kind | What it uses | Covers |
|---|---|---|
| Unit | pure Java | `Money` parsing/limits/overflow/formatting, `SettingsParser`, `ConfigManager` (defaults, invalid input, failed reload keeps old settings), `Lifecycle`, API contract |
| Integration | **real SQLite files** in per-test temp directories | store: accounts, starting balance once, UUID vs name identity, persistence across reopen and simulated plugin restart, schema migration/refusal, every transaction outcome, rollback of a failed transfer (injected fault), admin set/add/take invariants, many-thread and multi-connection concurrency (no overspend, no lost update, single account creation) |
| Command-level | real store/service/Brigadier trees, **mocked** `CommandSender`/`Player`/`Server` | permissions (unauthorized rejected, authorized succeeds), `/balance`, `/pay` (incl. duplicate guard and no success before commit), `/eco`, `/eco reload`, tab completion, join listener |

None of these run inside a Paper server. See below for what was run on one.

## Manual smoke test on a Paper server

Use a throwaway server directory.

1. **Start-up.** Put `SMP-Core-1.0.0.jar` and `SMP-Economy-1.0.0.jar` in `plugins/`, start. Expect
   `[SMP-Economy] SMP-Economy v1.0.0 enabled (currency $, database ...)` after SMP-Core's enabled line, and no
   `SEVERE`/`ERROR` lines. `plugins/SMP-Economy/config.yml` and `economy.db` exist.
2. **New player.** Join with a client. `/balance` shows `$100.00`. Leave, rename (or use a second account) and
   rejoin: the balance is unchanged.
3. **Payments.** With two players: `/pay <other> 25.50` -> both are notified; `/balance` shows 74.50 / 125.50.
   Try `/pay <self> 1`, `/pay nobody 1`, `/pay <other> 1.005`, `/pay <other> -1`, `/pay <other> 999999`
   (insufficient funds), each should be refused with a clear message and no balance change.
4. **Permissions.** As a non-op: `/balance <other>` and every `/eco ...` subcommand answer
   "You do not have permission to do that (requires ...)". Grant `smpeconomy.balance.others` only and confirm just
   that works.
5. **Admin.** As op or console: `/eco balance <p>`, `/eco add <p> 10`, `/eco take <p> 5`, `/eco set <p> 50`;
   each prints name + UUID and the console shows an `[ECO]` line naming the actor. `/eco take <p> 99999` and
   `/eco set <p> -1` are refused.
6. **Reload.** Edit `config.yml` (e.g. symbol to `EUR `), `/eco reload` -> applied. Set
   `balances.starting: "-5"`, `/eco reload` -> "Reload rejected; the previous settings remain active" with the
   problem listed and the old symbol still in effect. Restore the file.
7. **Restart.** `stop`, start again: balances are unchanged and rejoining does not grant the starting balance.
8. **Invalid startup config.** With the server stopped set `logging.debug: maybe`; start: SMP-Economy logs every
   problem and disables itself; SMP-Core stays enabled; `/balance` is an unknown command. Restore the file.
9. **Dependent plugin (optional).** Build `src/example` into a plugin (it needs `depend: [SMP-Core, SMP-Economy]`)
   and call `showBalance` / `pay` from a command.

## Verified on a real server

On **2026-10-23** SMP-Economy 1.0.0 and SMP-Core 1.0.0 were run on **Paper 1.21.11 build 132** (Java 21.0.12,
Windows 11), in a throwaway copy of the server binaries (not any existing server or world), driven through the
server **console only** (no game client was connected). Two accounts were inserted into `economy.db` with JDBC
while the server was stopped. Confirmed in that run:

- Both plugins load; SMP-Core enables first; the SQLite driver from Paper's class path works for the plugin;
  schema version 1 is created; `/balance`, `/pay` and `/eco` are registered.
- `/eco balance`, `add`, `take`, `set` (success and every refusal tried: insufficient funds, negative, 3 decimals,
  `1e3`, unknown player), `[ECO]` console audit lines with actor and UUIDs, `/balance <player>` by stored name,
  UUID lookup, `/pay` refused for the console.
- `/eco reload` with a valid change (symbol changes immediately), with an invalid file (rejected, all problems
  listed, old settings kept), and after fixing the file.
- Balances persisted across a full server stop/start; clean shutdown with no stack traces; SMP-Core removed the
  service registration when SMP-Economy was disabled.
- Startup with an invalid `config.yml`: SMP-Economy listed all problems and disabled itself, SMP-Core stayed
  enabled, the economy commands did not exist.

**Not tested on a real server:** anything needing a real player: account creation on join, `/balance` and `/pay`
as a player, notifications to online recipients, permission denials for non-ops, client-side tab completion,
multiple players paying concurrently, and a dependent plugin consuming `EconomyService`. Those paths are covered
only by the mock-based tests described above.

## Project layout

```
src/main/java/net/kaloyan/smpeconomy/
  SMPEconomy.java            plugin entry point; ordered startup with rollback
  Lifecycle.java             reverse-order cleanup stack
  DependencyValidator.java   checks Paper API and sqlite driver classes exist
  api/                       the only supported API (EconomyService, TransactionResult)
  money/                     Money (parsing, limits, conversion, formatting), InvalidAmountException
  config/                    EconomySettings, SettingsParser (validation), ConfigManager (load/reload)
  storage/                   EconomyStore (all SQL and money rules), SchemaMigrator, AccountRef
  service/                   EconomyServiceImpl (async facade + admin/lookup operations)
  command/                   EconomyCommands (Brigadier), Messages
  player/                    AccountInitListener
  log/                       DebugLogger
src/example/java/            compile-tested example consumer plugin (not packaged)
docs/API_USAGE.md            API documentation
```

## Known limitations

- A player must have joined at least once (with SMP-Economy installed) to have an account; `/pay` and
  `/balance <name>` cannot target anyone else. Names are resolved through the *last known name* stored on the
  account, so a player who renamed and has not rejoined is only found under their old name (or by UUID).
- On an **offline-mode** server UUIDs are derived from names, so identity is effectively the name there.
- There is no transaction history/ledger, no Vault provider, no leaderboard, no payment confirmation prompt, and no
  notification for payments to offline players.
- One server process should own the database file. Several connections to the same file are safe (SQLite's write
  lock; covered by tests with separate connections), but pointing several servers at one file is not a
  supported deployment and was not tested.
- If an account cannot be created at join (database error) it is logged at `SEVERE` and retried on the next join;
  until then that player has no balance and `/balance` says so.
- `/eco add`, `take` and `set` can bypass `transactions.maximum-amount`; they cannot exceed the maximum balance
  or the absolute limit, and cannot make a balance negative.
- The stored name is unique among accounts: when a different account joins with a name another account still
  holds, the older account loses its name (it stays reachable by UUID).
- `/reload` is not supported (as for SMP-Core); restart the server to change the jar. Only `config.yml` is
  reloadable, via `/eco reload`.
- Unknown keys in `config.yml` are ignored without a warning.
- Folia is not supported. Paper 1.21.11 is past Paper's support window; SMP-Economy was tested on build 132.
- `api-version: 1.21.11` makes the plugin refuse to load on Paper older than 1.21.11.
- A plugin that depends on SMP-Economy is still enabled by the server if SMP-Economy disables itself at startup;
  it must handle the service being absent (see `docs/API_USAGE.md`).

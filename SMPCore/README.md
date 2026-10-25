# SMP-Core

Foundation plugin for a custom survival SMP. Version 1 provides configuration, a shared API for
future plugins, a service registry, and the `/smpcore` command. It does not touch gameplay,
inventories, balances or persistent player data.

| | |
|---|---|
| Plugin name | `SMP-Core` |
| Version | `1.0.0` |
| Main class | `net.kaloyan.smpcore.SMPCore` |
| Server | Paper **1.21.x** (built against `paper-api 1.21.11-R0.1-SNAPSHOT`, `api-version: 1.21.11`) |
| Java | **21** |
| Folia | Not supported (the plugin does not declare `folia-supported`) |

> Version choices: Minecraft 1.21.11 and Java 21 were specified by the project owner. To target a
> different patch, change `paperApiVersion` in `build.gradle.kts` and `api-version` in `plugin.yml` together.

## Build

Requires a JDK 21 on the machine running Gradle. The Gradle wrapper (9.8.2, checksum-pinned) is included.

```
./gradlew build          # Linux/macOS/Git Bash
gradlew.bat build        # Windows cmd / PowerShell
```

Output: `build/libs/SMP-Core-1.0.0.jar`. Run only the tests with `./gradlew test`.

## Install

1. Stop the server (or be prepared to restart it; do not use `/reload`).
2. Copy `SMP-Core-1.0.0.jar` into the server's `plugins/` folder.
3. Start the server. `plugins/SMP-Core/config.yml` is created on first start.

## Configuration

`plugins/SMP-Core/config.yml` (identical to the bundled default, which is also
[`src/main/resources/config.yml`](src/main/resources/config.yml)):

```yaml
prefix: "<dark_aqua>[SMP]</dark_aqua>"   # MiniMessage; not blank; max 100 chars

logging:
  debug: false        # extra [DEBUG] lines in the console
  player-join: true   # log "Player joined: <uuid> (<name>)"
  player-quit: true   # log "Player left: <uuid> (<name>)"
```

- Missing or empty keys use the defaults above; the file is never rewritten.
- Every value is validated. A wrong type, a blank/overlong prefix, or malformed MiniMessage is an error.
- **At startup** an invalid file stops SMP-Core from enabling (with every problem listed in the console)
  rather than silently running on defaults.
- **On `/smpcore reload`** an invalid file is rejected as a whole: the previously active settings stay in
  effect and the problems are shown to the sender and logged.

## Commands and permissions

| Command | Permission | Description |
|---|---|---|
| `/smpcore` | none | Lists the subcommands the sender may use |
| `/smpcore info` | `smpcore.info` | Plugin version, enabled status, debug state |
| `/smpcore reload` | `smpcore.admin` | Re-reads `config.yml` (file is read off the main thread) |

| Permission | Default | Meaning |
|---|---|---|
| `smpcore.info` | op | Diagnostic information |
| `smpcore.admin` | op | Administrative commands; also grants `smpcore.info` |

What `/smpcore reload` reloads: **only** SMP-Core's own `config.yml` settings (prefix and logging flags).
It does not reload the plugin, its code, or any other plugin's configuration.

## For plugin developers

See [docs/API_USAGE.md](docs/API_USAGE.md) for how a future plugin depends on SMP-Core, reads
settings, and publishes/consumes shared services.

## Manual smoke test on a Paper server

Prerequisites: Java 21, a Paper 1.21.x server jar (from papermc.io; accepting the Minecraft EULA in
`eula.txt` is your decision), and the built `SMP-Core-1.0.0.jar`. Use a throwaway server directory.

1. **Start-up.** Put the jar in `plugins/`, start the server. Expect in the console:
   `[SMP-Core] SMP-Core v1.0.0 enabled on Paper 1.21.x (debug=false)` and no `SEVERE` lines.
   Confirm `plugins/SMP-Core/config.yml` exists and matches the default.
2. **Plugin listing.** In the console run `plugins`: SMP-Core is green.
3. **Info.** As an op in game (the console also has all permissions): `/smpcore info` shows `SMP-Core v1.0.0`, `Status: enabled`, `Debug logging: off`, each line starting with
   the `[SMP]` prefix.
4. **Permissions.** As a non-op player: `/smpcore info` and `/smpcore reload` both answer
   "You do not have permission ..." naming the missing permission. Then grant `smpcore.info` only
   (e.g. with a permissions plugin or `op` toggling) and confirm `info` works but `reload` is still denied.
5. **Bad arguments.** `/smpcore foo` -> "Unknown subcommand 'foo'..."; `/smpcore info x` -> "Too many arguments...".
6. **Player events.** Join and leave with a client. Console shows
   `Player joined: <uuid> (<name>)` and `Player left: <uuid> (<name>)`.
7. **Valid reload.** Edit `config.yml`: set `logging.debug: true` and `prefix: "<green>[Test]</green>"`.
   Run `/smpcore reload` -> "Configuration reloaded."; `/smpcore info` now shows the new prefix and
   `Debug logging: on`; rejoining produces additional `[DEBUG]` lines.
8. **Invalid reload.** Set `logging.debug: maybe` (or `prefix: ""`) and run `/smpcore reload`. Expect
   "Reload rejected; the previous settings remain active." plus the specific problem; `info` still shows the
   settings from step 7.
9. **Broken YAML reload.** Put `logging: [` in the file and reload: rejected with a "not valid YAML" message.
   Restore a valid file and reload again to confirm recovery.
10. **Invalid startup.** With the server stopped, set `logging.debug: maybe`, start: SMP-Core logs the problems
    at `ERROR`/`SEVERE` level and disables itself. `/smpcore` must then be an unknown command. A dependent plugin
    is still enabled by the server in this case, so it must check `isPluginEnabled("SMP-Core")` before using any
    SMP-Core class (see `docs/API_USAGE.md`). Restore the file.
11. **Shutdown.** Run `stop`. Expect `SMP-Core disabled.` and no stack traces.
12. **Dependent plugin (optional).** Build the example from `docs/API_USAGE.md`, install it, and confirm its
    startup message shows the Core version and that disabling it removes its registered service
    (`Removed 1 shared service(s) registered by ...` in the console).

## Verified on a real server

Paper 1.21.11 build 132 (Java 21.0.12, Windows 11), scripted through the server console on 2026-10-22, using
a throwaway dependent plugin that depends on SMP-Core. Covered: first start creating the default config,
startup and clean shutdown, `/smpcore` (usage, `info`, unknown subcommand, extra arguments, `reload`),
reload with a valid change / invalid values / malformed YAML / deleted file, missing keys falling back to
defaults, settings visible to a dependent plugin after reload, `[DEBUG]` output, duplicate and missing service
errors, service removal when the owner is disabled, and startup with an invalid config (clear error, no
command, API not published, no stack trace). A follow-up session with a real client covered join/quit logging
and the player-facing command and permission behaviour (see limitations for what remains).

## Project layout

```
src/main/java/net/kaloyan/smpcore/
  SMPCore.java              plugin entry point; ordered startup with rollback
  Lifecycle.java            reverse-order cleanup stack
  CoreApi.java              SMPAPI implementation; goes inert after shutdown
  DependencyValidator.java  checks the required Paper API classes exist
  api/                      the only supported API (SMPAPI, ServiceRegistry, CoreSettings, exceptions)
  config/                   SettingsParser (validation), ConfigManager (load/reload), ReloadResult
  service/                  SimpleServiceRegistry, ServiceOwnerCleanup
  command/                  SmpCoreCommand (Brigadier), Messages
  player/                   PlayerLifecycleListener
  log/                      DebugLogger
```

## Design notes

- **No static singletons.** The API is published through Bukkit's `ServicesManager`; SMP-Core holds all
  state in objects wired together in `SMPCore#start`.
- **Startup is transactional.** Each step that succeeds registers a cleanup; if any later step throws, the
  cleanups run in reverse order, the plugin disables itself, and the API is never published.
  The API and command are the last things registered.
- **Shutdown** invalidates the API handle and the registry, so references that other plugins kept fail with
  `IllegalStateException` instead of reaching dead services.
- **Config is read by SMP-Core, not `JavaPlugin#reloadConfig()`**, because Bukkit's reload silently replaces a
  malformed file with an empty configuration.
- **Blocking I/O**: the reload file read runs on Bukkit's async scheduler; feedback is sent back on the main
  thread. The initial read happens once during startup.
- **Commands** use Paper's Brigadier lifecycle registration (`LifecycleEvents.COMMANDS`), not `plugin.yml` commands.
- **Dependencies**: none at runtime. Test-only: JUnit 5 and Mockito. No database.

## Known limitations

- Confirmed on a real server with a real client (Paper 1.21.11 build 132): join and runtime-quit logging
  (`Player joined/left: <uuid> (<name>)`, seen in the server log); non-op denials for `/smpcore`, `/smpcore info`
  and `/smpcore reload`; op access to `info` and `reload`; the three `[SMP]`-prefixed `info` lines; and a successful
  reload. The chat-side results are the tester's in-game observations (command replies go to the player's chat,
  not the server log); the server log independently shows the op's reload executing and the non-op's not.
- Players still online when the server stops are not logged as having left: Paper disables plugins
  before it disconnects players, so SMP-Core's quit listener is already gone by then.
- Folia is not supported.
- Paper 1.21.11 is past Paper's support window (the server prints an update notice); SMP-Core was tested on build 132.
- A dependent plugin is still enabled by the server when SMP-Core disables itself at startup; it must
  check `isPluginEnabled("SMP-Core")` first (see `docs/API_USAGE.md`).
- A plugin's registrations are removed when the server fires its `PluginDisableEvent`, i.e. before its own `onDisable`.
- Only `config.yml` is reloadable, and only prefix/logging settings exist. Other plugins are not told when a reload happens;
  they must re-read `SMPAPI#getSettings()` when they need a value.
- Unknown keys in `config.yml` (for example a misspelled `loging:`) are ignored without a warning.
- Subcommand names appear in tab completion for senders who may not run them; running them gives a clear denial.
- The service registry keys on exact class and never closes services; owners must release their own resources.
  A plugin that registers a service after it has already been disabled would not be cleaned up automatically.
- Debug output is logged at INFO with a `[DEBUG]` marker (not FINE), because most servers hide lower levels.
- The unexpected-exception branch inside `ConfigManager#reloadNow` (a `RuntimeException` that is not an invalid
  config) has no dedicated unit test.
- `api-version: 1.21.11` makes the plugin refuse to load on Paper older than 1.21.11.

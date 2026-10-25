# SMP-Punish 1.0.0 (author: Kaloyan)

Custom kicks, bans, mutes and warnings for Paper 1.21.11 (Java 21). The ban and kick screens, every announcement and the reason
shortcuts are in `config.yml` (MiniMessage). Replaces the vanilla `/ban`, `/kick` and `/pardon`.

Commands
- `/ban <player> [time|preset] [reason] [-s]` (permanent unless you give a time), `/tempban <player> <time> [reason]`, `/ipban` (also bans the
  address they last used, so alts on it cannot join), `/unban <player> [reason]`.
- `/kick <player> [reason] [-s]`, `/mute <player> [time|preset] [reason]` (`/tempmute`), `/unmute`.
- `/warn <player> <reason|preset>`: warnings add up (default: 2 = 1 h mute, 3 = 1 day ban, 5 = 7 day ban; warnings older than 30 days stop counting).
- `/history <player> [page]`, `/punishment <number>` and `/punishment remove <number> [reason]`, `/banlist`, `/alts <player>` (same address).
- `/punish reload`.
- Times: `30m`, `12h`, `7d`, `2w`, `1mo`, `1y`, `1d12h`, `perm`. Presets: `cheating`, `xray`, `grief`, `spam`, `toxic`, `advertising`, `dupe`,
  `ban-evasion` (edit them). `-s` at the end keeps it from being announced to everybody (staff with `smppunish.notify` still see it).
- Staff without `smppunish.long` cannot ban or mute permanently or for more than 30 days.
- Players with `smppunish.exempt` cannot be punished by players (the console still can). Muted players cannot chat or use /msg, /me, /r ...

Safety: changes are saved before they take effect (a failed save changes nothing), a damaged `punishments.yml` stops the plugin
instead of being overwritten, and everything staff or players typed is shown as plain text on the screens (no colours or click events).
Records are kept after a ban is lifted. Names must have joined once (or be in the server's user cache) to be punished.

Tests: `./gradlew build` (18 tests); `boot-test.sh` runs the commands on a throwaway server. The login screen and chat blocking were not tried
with a connected player. SMP-Catch's threshold commands can call `ban %player% ...` from here.

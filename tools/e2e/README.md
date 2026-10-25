# End-to-end tests with real game clients

`e2e.js` (44 checks: menus, buying, kits, sell, daily, auction between two players, bounty, messages, hidden staff commands ...) and `authbot.js` (18 checks of SMP-Auth)
play against a THROWAWAY server (never your real one) using mineflayer bots.

Setup once: `mkdir bots && cd bots && npm init -y && npm i mineflayer`.
Server: copy `server.jar`, `libraries`, `versions`, `cache` to a new folder; `server.properties`: `online-mode=false`, `enforce-secure-profile=false`, a free port, `level-type=minecraft\:flat`;
put the plugin jars in `plugins`; for `e2e.js` also LuckPerms + Vault + SMP-VaultBridge + `LuckPerms-setup/haven.json.gz` (run `lp import haven` in the console before the bots join) and an
`ops.json` that makes the bot named Tester an operator (its offline UUID is the MD5 name UUID of "OfflinePlayer:Tester").
Run: `BOTDIR=<path of bots> PORT=<server port> node e2e.js`. Use a fresh server for every run (kit and daily cooldowns).

# SMP-Kits 1.0.0 (author: Kaloyan)

One kit per rank for Paper 1.21.11. Guardian (admin) gets none; the Founder plays and gets all kits.

- `/kit` opens the menu (left-click claims, right-click shows the contents), `/kit <name>` claims, `/kit preview <name>`.
- Kits: Settler (every 3 days), Keeper (2 days), Beacon (1 day), Lookout (1 day), Warden (12 hours). Items, enchantments, cooldowns ("once" for
  a one-time kit), names and extra console commands are all in `config.yml`.
- Access is the permission `smpkits.kit.<id>`; LuckPerms ranks inherit the ones below. `excluded-groups` (guardian) are refused by main rank.
- `/kit` is only visible to ranks with `smpkits.use`, so ordinary players do not see it.
- A kit is claimed only if every item fits; the claim is saved first, then the items are given. A damaged `claims.yml` stops the plugin.
- `/kitadmin give <player> <kit>`, `reset <player> <kit>`, `list`, `reload` (`smpkits.admin`).
Tests: `./gradlew build` (8 tests); `LuckPerms-setup/haven-new-plugins.txt` has the permission lines.

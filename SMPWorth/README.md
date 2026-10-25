# SMP-Worth

Puts **"Worth: <price>"** into the lore of every item (Paper 1.21.11, Java 21), from your price sheet
(`donutsmp_custom_prices.json`: a base value per item and a bonus per enchantment level).

## Install
Copy `build/libs/SMP-Worth-1.0.0.jar` into `plugins/` and restart. On the first start it copies the sheet to `plugins/SMP-Worth/prices.json`
(edit that file, or replace it with a newer sheet, then `/worth reload`). A damaged sheet disables the plugin with a clear message instead of
silently pricing everything at 0.

## What a price is
`worth = base_value + sum(bonus_per_level * level)` over the item's enchantments, rounded to 2 decimals, exactly as the sheet's
`pricing_model` says. An enchanted book is the book plus what is stored in it (the sheet's separate `enchanted_book_<name>` entries are not
used). Curses lower the price. Minecraft's names `binding_curse` and `vanishing_curse` are understood as the sheet's
`curse_of_binding` and `curse_of_vanishing`. Example: a diamond pickaxe (90) with Efficiency V, Unbreaking III, Fortune III and Mending
shows `Worth: 213`.

## Items the sheet does not list
The sheet has 1,404 items; Minecraft 1.21.11 has 277 more (copper tools and armor, shelves, pottery sherds, harnesses, waxed copper, the
totem of undying...). `fallbacks` in `config.yml` price them: slabs, stairs and walls like the sheet's block values, waxed or weathered
copper like plain copper, spears like swords, nautilus armor like horse armor, the totem at 120, spawn eggs at 0, and so on. Rules are
tried in order, the first match wins, and each is either a fixed `value` or `same-as` another item. **Every one of the 277 gets a price**
(`/worth missing` lists what is still without one: nothing at the moment). Review the numbers I picked; they are my estimates.

## Where the line appears
It is written into the real item, so every item of one kind shows the same text and keeps stacking.
- Player inventories (hotbar, armor, off hand): every `scan-ticks` (10 = twice a second), and when a player joins.
- Chests, barrels, shulker boxes, hoppers, ender chests, chest minecarts: when they are opened and closed.
- Items on the ground: when a player picks them up.
- **Not touched:** menus made by other plugins (shop, crates, auction house), players in creative mode (their client makes the items), and
  items inside a shulker box item.
When the price changes (an enchantment is added, or you edit the sheet and reload), the old line is replaced, never duplicated; other
lore (spawner, drill...) is kept and the Worth line stays last.

## Commands
`/worth` (the item in your hand: each and for the whole stack), `/worth <item>`, `/worth reload`, `/worth missing`, `/worth selftest` (admin,
`smpworth.admin`). `selftest` builds items on the real server and checks the lines, that applying twice changes nothing and that items still
stack.

## Settings (`config.yml`)
`lore.format` (MiniMessage, needs `<price>`), `lore.hide-zero` (no line at all on items worth 0), `scan-ticks`, `skip-creative`,
`fallbacks`, and the messages.

## Tested
Unit tests: the sheet (1,404 items, enchantment sums, curses and their aliases, broken sheets), the fallback rules (all 277 unlisted items,
looping rules), the lore line (added once, replaced, other lore untouched, idempotent), config validation. A throwaway server ran
`/worth selftest` (passed) and `/worth missing` (0). Not tested with a real player: how it looks, and the inventory scanning with
many players online.

## Note about the sheet
Its own metadata says: "Custom balancing draft. Not official DonutSMP /sell values. Review before using as a production economy." The
lore only displays the numbers; nothing is sold or bought with them yet.

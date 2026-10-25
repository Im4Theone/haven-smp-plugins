# SMP-Drill

The **Amethyst Drill**: a netherite pickaxe with maxed enchantments that mines a **5x5 tunnel** with every swing (Paper 1.21.11,
Java 21).

## Install
Copy `build/libs/SMP-Drill-1.0.0.jar` into `plugins/` and restart. No other plugin is needed. Give yourself one with
`/drill give <player> [amount]` (permission `smpdrill.admin`, default op). SMP-Crates can hand it out as a rare reward: the
Amethyst and Crimson crates in the shipped config have it.

## The item
Netherite pickaxe named "Amethyst Drill" (pink), Efficiency V, Unbreaking III, Fortune III and Mending. It carries a hidden tag, so only
drills made by this plugin work as drills (renaming an ordinary pickaxe does nothing). All of it is in `config.yml`: name, lore,
enchantments, and `unbreakable` if you want it to last forever.

## What one swing does
When you mine a block with the drill, the rest of the wall around it breaks too: a 5x5 square, perpendicular to the face you hit
(mining forward digs a tunnel, mining down digs a shaft). When you mine at normal reach the tunnel floor is the floor you stand on, so the
drill does not dig the ground away under you; for blocks far above or below you it is centred on the block. The drops follow your
Fortune level. **Sneak** while mining to break just one block.

- Every extra block first goes through a normal block-break check, exactly as if you broke it by hand, so SMP-Protect regions,
  crate blocks and any other plugin can refuse it; refused blocks stay.
- Chests, signs, spawners, other blocks with data, liquids and unbreakable blocks (bedrock) are never touched.
- `tunnel.size` (odd, 3 to 9), `tunnel.depth` (layers per swing), `tunnel.wear-per-extra-block`, `tunnel.sneak-mines-one` and
  `disabled-worlds` are in the config; `/drill reload` applies them (an invalid file is rejected and the old settings stay).

## Tested
Unit tests: the tunnel shape (5x5 wall, floor rule, up/down, depth, 3x3 and 9x9), config validation, and the swing itself with Bukkit
mocked: it asks every one of the 24 other blocks, leaves a block another plugin refuses, ignores sneaking and other tools, and never
drills its own events again. A throwaway server loads it and accepts the enchantment names. Mining with a real player (the face
detection, the drops, the Fortune effect) was **not** tested in game.

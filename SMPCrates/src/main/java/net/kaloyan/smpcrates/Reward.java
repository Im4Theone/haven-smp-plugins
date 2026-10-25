package net.kaloyan.smpcrates;

import java.util.Map;

/**
 * One thing a crate can give.
 *
 * @param kind        what it is
 * @param weight      relative chance (higher = more likely)
 * @param material    the item given (ITEM) or shown in menus (the other kinds)
 * @param amount      items for ITEM; money for MONEY; shards for SHARDS
 * @param name        MiniMessage display name; empty = the default one
 * @param enchants    enchantment name to level (ITEM only)
 * @param command     console command with {@code %player%} (COMMAND only)
 * @param broadcast   whether everybody is told when somebody wins it
 */
public record Reward(Kind kind, int weight, String material, long amount, String name, Map<String, Integer> enchants,
                     String command, boolean broadcast) {

    /** The kinds of reward. */
    public enum Kind { ITEM, MONEY, SHARDS, COMMAND }

    public Reward {
        enchants = Map.copyOf(enchants);
        name = name == null ? "" : name;
        command = command == null ? "" : command;
    }
}

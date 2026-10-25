package net.kaloyan.smpcrates;

import java.util.List;
import java.util.Random;

/**
 * A kind of crate.
 *
 * @param id       lower-case name used in commands and data, e.g. {@code common}
 * @param display  MiniMessage name shown in menus and messages
 * @param material the item that stands for the crate in menus
 * @param rewards  what it can give, at least one
 */
public record CrateType(String id, String display, String material, List<Reward> rewards) {

    public CrateType {
        rewards = List.copyOf(rewards);
    }

    /** @return the sum of all weights */
    public int totalWeight() {
        return rewards.stream().mapToInt(Reward::weight).sum();
    }

    /**
     * Picks a reward, each with a chance proportional to its weight.
     *
     * @param random the random source
     * @return the reward
     */
    public Reward pick(Random random) {
        int roll = random.nextInt(totalWeight());
        for (Reward reward : rewards) {
            roll -= reward.weight();
            if (roll < 0) {
                return reward;
            }
        }
        return rewards.get(rewards.size() - 1);
    }

    /**
     * @param reward one of this crate's rewards
     * @return its chance in percent
     */
    public double chancePercent(Reward reward) {
        return 100.0 * reward.weight() / totalWeight();
    }
}

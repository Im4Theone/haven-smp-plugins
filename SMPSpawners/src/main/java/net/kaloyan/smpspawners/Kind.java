package net.kaloyan.smpspawners;

import java.util.List;
import java.util.Random;

/**
 * What the spawner of one creature produces.
 *
 * @param id    the creature's name in capitals, e.g. {@code IRON_GOLEM}
 * @param exp   experience points one stacked spawner adds in every round
 * @param drops what it can drop; each round every stacked spawner makes one pick
 */
public record Kind(String id, int exp, List<Drop> drops) {

    /**
     * One possible drop.
     *
     * @param item   the item's name in capitals, e.g. {@code BONE}
     * @param min    the least it gives when picked (0 means the pick can come up empty)
     * @param max    the most it gives when picked
     * @param weight how likely it is compared to the other drops
     */
    public record Drop(String item, int min, int max, int weight) {
    }

    public Kind {
        drops = List.copyOf(drops);
    }

    /** @return the sum of all weights */
    public int totalWeight() {
        return drops.stream().mapToInt(Drop::weight).sum();
    }

    /**
     * @param drop one of this kind's drops
     * @return its chance in percent
     */
    public double percent(Drop drop) {
        return 100.0 * drop.weight() / totalWeight();
    }

    /**
     * Picks one drop by weight.
     *
     * @param random the random source
     * @return the drop
     */
    public Drop pick(Random random) {
        int roll = random.nextInt(totalWeight());
        for (Drop drop : drops) {
            roll -= drop.weight();
            if (roll < 0) {
                return drop;
            }
        }
        return drops.get(drops.size() - 1);
    }
}

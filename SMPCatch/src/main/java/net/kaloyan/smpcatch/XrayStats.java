package net.kaloyan.smpcatch;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Watches one player's mining. Every rock or ore block they break counts as one sample; the interesting ones are rare ores that were
 * hidden inside solid rock when they were broken (an honest miner mostly finds ore that a cave or tunnel has already shown). If
 * hidden rare ores make up too much of the recent samples, the player may be using x-ray.
 */
public final class XrayStats {

    private final int window;
    private final int minSamples;
    private final double ratio;
    private final Deque<Boolean> samples = new ArrayDeque<>();
    private int hidden;

    /**
     * @param window     how many recent samples are kept
     * @param minSamples how many samples are needed before judging
     * @param ratio      the share of hidden rare ores (0..1) that raises an alert
     */
    public XrayStats(int window, int minSamples, double ratio) {
        this.window = window;
        this.minSamples = minSamples;
        this.ratio = ratio;
    }

    /**
     * Adds a sample.
     *
     * @param hiddenRare {@code true} if a rare ore was broken that was hidden inside solid rock
     * @return {@code true} if the share is now too high; the window is then cleared so the same mining is not reported twice
     */
    public boolean record(boolean hiddenRare) {
        samples.addLast(hiddenRare);
        if (hiddenRare) {
            hidden++;
        }
        while (samples.size() > window) {
            if (samples.removeFirst()) {
                hidden--;
            }
        }
        if (samples.size() >= minSamples && hidden >= 3 && (double) hidden / samples.size() >= ratio) {
            samples.clear();
            hidden = 0;
            return true;
        }
        return false;
    }

    /** @return how many samples are kept now */
    public int samples() {
        return samples.size();
    }

    /** @return how many of them are hidden rare ores */
    public int hidden() {
        return hidden;
    }
}

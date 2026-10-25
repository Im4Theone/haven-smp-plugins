package net.kaloyan.smpworth;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** What a stack is paid. Pure arithmetic: worth times amount, times the share that is left of a worn tool, times the sell percent. */
public final class SellMath {

    private SellMath() {
    }

    /**
     * @param worth      the worth of ONE item from the price sheet
     * @param amount     how many items
     * @param damage     the item's damage (0 for undamaged items)
     * @param maxDamage  the item's durability (0 for items that do not wear)
     * @param percent    the percent of the worth that is paid (85 = 15 percent less than the buy price)
     * @return the payment for the whole stack in hundredths, rounded down to a hundredth in the server's favour
     */
    public static long cents(double worth, int amount, int damage, int maxDamage, BigDecimal percent) {
        BigDecimal factor = maxDamage <= 0 ? BigDecimal.ONE
                : BigDecimal.valueOf(Math.max(0, maxDamage - Math.max(0, damage))).divide(BigDecimal.valueOf(maxDamage), 6, RoundingMode.DOWN);
        BigDecimal paid = BigDecimal.valueOf(worth).multiply(BigDecimal.valueOf(amount)).multiply(factor).multiply(percent).movePointLeft(2);
        return paid.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact();
    }
}

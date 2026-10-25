package net.kaloyan.smpworth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SellMathTest {

    private static final BigDecimal P85 = BigDecimal.valueOf(85);

    @Test
    void sellingPaysFifteenPercentLessThanTheWorth() {
        assertEquals(2125, SellMath.cents(25, 1, 0, 0, P85), "a diamond worth 25 sells for 21.25");
        assertEquals(2125 * 64, SellMath.cents(25, 64, 0, 0, P85));
        assertEquals(170000, SellMath.cents(2, 1000, 0, 0, P85), "1000 items worth 2 each: 1700.00");
    }

    @Test
    void theShopPriceIsAlwaysAtLeastWhatSellingPays() {
        for (double worth : new double[] {0.5, 1.5, 2.5, 4, 45, 1250}) {
            for (int amount : new int[] {1, 3, 16, 64}) {
                long buy = Math.round(worth * amount * 100);
                long sell = SellMath.cents(worth, amount, 0, 0, P85);
                assertTrue(sell < buy, worth + " x " + amount);
                assertTrue(sell >= buy * 0.84, "about 15 percent less, not more: " + worth + " x " + amount);
            }
        }
    }

    @Test
    void roundingFavoursTheServer() {
        assertEquals(212, SellMath.cents(2.5, 1, 0, 0, P85), "2.125 becomes 2.12");
        assertEquals(0, SellMath.cents(0.01, 1, 0, 0, P85), "less than a hundredth pays nothing");
    }

    @Test
    void wornToolsArePaidInProportion() {
        assertEquals(1062, SellMath.cents(25, 1, 125, 250, P85), "half worn: half of 21.25");
        assertEquals(0, SellMath.cents(25, 1, 250, 250, P85), "a broken tool pays nothing");
        assertEquals(2125, SellMath.cents(25, 1, 0, 250, P85));
    }
}

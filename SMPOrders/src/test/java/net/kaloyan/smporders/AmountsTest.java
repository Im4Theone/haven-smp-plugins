package net.kaloyan.smporders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    void countsAcceptShorthandButOnlyWholePositiveNumbers() {
        assertEquals(64, Amounts.count("64"));
        assertEquals(1000, Amounts.count("1k"));
        assertEquals(1500, Amounts.count("1.5k"));
        assertEquals(2_000_000, Amounts.count("2m"));
        assertEquals(-1, Amounts.count("1.5"));
        assertEquals(-1, Amounts.count("0"));
        assertEquals(-1, Amounts.count("-3"));
        assertEquals(-1, Amounts.count("abc"));
        assertEquals(-1, Amounts.count("5b"));
        assertEquals(-1, Amounts.count(""));
    }

    @Test
    void pricesAreInHundredthsWithAtMostTwoDecimals() {
        assertEquals(250, Amounts.cents("2.5"));
        assertEquals(1, Amounts.cents("0.01"));
        assertEquals(150_000, Amounts.cents("1.5k"));
        assertEquals(123_456, Amounts.cents("$1,234.56"));
        assertEquals(-1, Amounts.cents("0.001"));
        assertEquals(-1, Amounts.cents("0"));
        assertEquals(-1, Amounts.cents("1e5"));
        assertEquals(-1, Amounts.cents("free"));
    }

    @Test
    void formatting() {
        assertEquals("$1,234.5", Amounts.money(123_450));
        assertEquals("$0.01", Amounts.money(1));
        assertEquals("2d 3h", Amounts.duration(2 * 86_400_000L + 3 * 3_600_000L + 5000));
        assertEquals("5h 10m", Amounts.duration(5 * 3_600_000L + 10 * 60_000L));
        assertEquals("1m", Amounts.duration(10));
    }
}

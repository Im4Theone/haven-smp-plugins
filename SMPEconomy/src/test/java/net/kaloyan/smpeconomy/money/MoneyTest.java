package net.kaloyan.smpeconomy.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import net.kaloyan.smpeconomy.money.InvalidAmountException.Reason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static Reason reasonOf(String text) {
        return assertThrows(InvalidAmountException.class, () -> Money.parse(text)).reason();
    }

    // ---- valid amounts ----

    @ParameterizedTest
    @CsvSource({
            "0,0",
            "0.00,0",
            "5,500",
            "5.5,550",
            "5.05,505",
            "0.01,1",
            "12.50,1250",
            "5.500,550",
            "1000000,100000000",
            "1000000000000000.00,100000000000000000",
    })
    void validTextParsesToExactMinorUnits(String text, long expectedMinor) throws Exception {
        BigDecimal parsed = Money.parse(text);

        assertEquals(2, parsed.scale(), "parsed amounts are normalised to two decimals");
        assertEquals(expectedMinor, Money.toMinor(parsed));
        assertEquals(0, Money.fromMinor(expectedMinor).compareTo(parsed));
    }

    @Test
    void minorUnitsRoundTripWithoutLoss() throws Exception {
        for (long minor : new long[] {0, 1, 99, 100, 101, 123_456_789, Money.HARD_MAX_MINOR}) {
            assertEquals(minor, Money.toMinor(Money.fromMinor(minor)));
        }
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreExactSoTheyAreAccepted() throws Exception {
        assertEquals(550, Money.toMinor(new BigDecimal("5.5000000")));
        assertEquals(500, Money.toMinor(new BigDecimal("5E+0")));
        assertEquals(500_000, Money.toMinor(new BigDecimal("5E+3")));
    }

    // ---- zero and negative ----

    @Test
    void zeroIsAllowedWhereZeroIsAllowedButNotWherePositiveIsRequired() throws Exception {
        assertEquals(0, Money.toNonNegativeMinor(BigDecimal.ZERO));
        assertEquals(Reason.ZERO, assertThrows(InvalidAmountException.class,
                () -> Money.toPositiveMinor(new BigDecimal("0.00"))).reason());
    }

    @Test
    void negativeAmountsAreRejectedWhereTheyAreNotAllowed() {
        assertEquals(Reason.NEGATIVE, assertThrows(InvalidAmountException.class,
                () -> Money.toNonNegativeMinor(new BigDecimal("-0.01"))).reason());
        assertEquals(Reason.NEGATIVE, assertThrows(InvalidAmountException.class,
                () -> Money.toPositiveMinor(new BigDecimal("-5"))).reason());
    }

    @Test
    void parseAcceptsNegativeTextSoCallersCanReportItSpecifically() throws Exception {
        assertEquals(-500, Money.toMinor(Money.parse("-5")));
        assertEquals(0, Money.toMinor(Money.parse("-0")));
    }

    // ---- precision ----

    @ParameterizedTest
    @ValueSource(strings = {"1.001", "0.005", "5.555", "0.0000001", "1.23456789012345678901"})
    void tooManyDecimalPlacesAreRejectedNotRounded(String text) {
        assertEquals(Reason.PRECISION, reasonOf(text));
        assertEquals(Reason.PRECISION, assertThrows(InvalidAmountException.class,
                () -> Money.toMinor(new BigDecimal(text))).reason());
    }

    @Test
    void tinyValueWithHugeScaleIsRejectedAsPrecisionWithoutBlowingUp() {
        assertEquals(Reason.PRECISION, assertThrows(InvalidAmountException.class,
                () -> Money.toMinor(new BigDecimal("1E-999999999"))).reason());
    }

    // ---- malformed ----

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1e3", "1E3", "+5", "5+", "--5", "5-", ".5", "5.", "1,000", "1 000", " 5", "5 ",
            "NaN", "Infinity", "0x10", "1_000", "5.5.5", "٣", "１２", "$5", "5$", "-", "-.5", "."})
    void malformedTextIsRejected(String text) {
        assertEquals(Reason.MALFORMED, reasonOf(text));
    }

    @Test
    void missingTextIsRejected() {
        assertEquals(Reason.MISSING, reasonOf(null));
        assertEquals(Reason.MISSING, reasonOf(""));
        assertEquals(Reason.MISSING, assertThrows(InvalidAmountException.class, () -> Money.toMinor(null)).reason());
    }

    @Test
    void absurdlyLongInputIsRejectedBeforeItIsParsed() {
        assertEquals(Reason.MALFORMED, reasonOf("1".repeat(Money.MAX_INPUT_LENGTH + 1)));
        assertEquals(Reason.MALFORMED, reasonOf("1".repeat(100_000)));
    }

    @Test
    void errorMessagesDoNotEchoTheInput() {
        InvalidAmountException e = assertThrows(InvalidAmountException.class, () -> Money.parse("<red>evil"));

        assertEquals(false, e.getMessage().contains("evil"));
    }

    // ---- size and overflow ----

    @Test
    void theHardMaximumIsAcceptedAndOneCentMoreIsNot() throws Exception {
        assertEquals(Money.HARD_MAX_MINOR, Money.toMinor(Money.HARD_MAX));
        assertEquals(Money.HARD_MAX_MINOR, Money.toMinor(Money.parse("1000000000000000.00")));
        assertEquals(Reason.TOO_LARGE, reasonOf("1000000000000000.01"));
        assertEquals(Reason.TOO_LARGE, reasonOf("-1000000000000000.01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000000000", "92233720368547758.07", "92233720368547758.08",
            "9223372036854775807", "99999999999999999999999999999999"})
    void valuesBeyondStorageLimitsAreRejected(String text) {
        assertEquals(Reason.TOO_LARGE, reasonOf(text));
    }

    @Test
    void bigDecimalsThatWouldOverflowALongAreRejectedSafely() {
        assertEquals(Reason.TOO_LARGE, assertThrows(InvalidAmountException.class,
                () -> Money.toMinor(new BigDecimal("9223372036854775807"))).reason());
        assertEquals(Reason.TOO_LARGE, assertThrows(InvalidAmountException.class,
                () -> Money.toMinor(new BigDecimal("1E+999999999"))).reason());
        assertEquals(Reason.TOO_LARGE, assertThrows(InvalidAmountException.class,
                () -> Money.toMinor(new BigDecimal(Long.MAX_VALUE).movePointLeft(2))).reason());
    }

    @Test
    void sumOfTwoMaximumAmountsStillFitsInALong() {
        assertEquals(2 * Money.HARD_MAX_MINOR, Math.addExact(Money.HARD_MAX_MINOR, Money.HARD_MAX_MINOR));
    }

    // ---- formatting ----

    @Test
    void formatShowsSymbolGroupingAndExactlyTwoDecimals() {
        assertEquals("$0.00", Money.format("$", BigDecimal.ZERO));
        assertEquals("$5.00", Money.format("$", new BigDecimal("5")));
        assertEquals("$1,234.50", Money.format("$", new BigDecimal("1234.5")));
        assertEquals("$1,000,000.01", Money.format("$", new BigDecimal("1000000.01")));
        assertEquals("-$5.00", Money.format("$", new BigDecimal("-5")));
        assertEquals("€10.00", Money.format("€", new BigDecimal("10")));
    }

    @Test
    void formatRefusesToRound() {
        assertThrows(IllegalArgumentException.class, () -> Money.format("$", new BigDecimal("1.005")));
        assertThrows(IllegalArgumentException.class, () -> Money.format("$", null));
    }
}

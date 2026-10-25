package net.kaloyan.smpeconomy.money;

/**
 * Thrown when a monetary amount cannot be accepted. The message is written for the person who typed
 * the amount and never echoes the offending input.
 */
public final class InvalidAmountException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why an amount was rejected. */
    public enum Reason {
        /** No amount was supplied. */
        MISSING,
        /** Not a plain decimal number such as {@code 12} or {@code 12.50}. */
        MALFORMED,
        /** Below zero where zero or more is required. */
        NEGATIVE,
        /** Exactly zero where a positive amount is required. */
        ZERO,
        /** More than two decimal places; would need rounding. */
        PRECISION,
        /** Larger than {@link Money#HARD_MAX} or too large for storage. */
        TOO_LARGE
    }

    private final Reason reason;

    InvalidAmountException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns the machine-readable reason.
     *
     * @return why the amount was rejected
     */
    public Reason reason() {
        return reason;
    }
}

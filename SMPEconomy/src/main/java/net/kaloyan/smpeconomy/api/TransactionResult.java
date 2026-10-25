package net.kaloyan.smpeconomy.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Outcome of a money-changing call on {@link EconomyService}. A transaction either happened completely
 * ({@link Status#SUCCESS}) or not at all; a failure never leaves a partial change behind.
 *
 * @param status             what happened; never {@code null}
 * @param message            short English description for logs and diagnostics. It is not meant to be shown to
 *                           players verbatim; switch on {@code status} to build your own message
 * @param balance            on success, the balance of the affected account after the transaction (for a
 *                           transfer, the payer); {@code null} on failure
 * @param counterpartBalance on a successful transfer, the recipient's balance after the transaction;
 *                           {@code null} otherwise
 */
public record TransactionResult(Status status, String message, BigDecimal balance, BigDecimal counterpartBalance) {

    /** Why a transaction succeeded or failed. */
    public enum Status {
        /** The transaction was committed. */
        SUCCESS,
        /** The amount is missing, zero, negative, has more than two decimals, or is beyond the storable maximum. */
        INVALID_AMOUNT,
        /** The amount is valid but larger than the configured maximum transaction amount. */
        AMOUNT_EXCEEDS_LIMIT,
        /** The account that would be debited has less money than the amount. */
        INSUFFICIENT_FUNDS,
        /** The credit would push the receiving account above the configured maximum balance. */
        BALANCE_LIMIT_EXCEEDED,
        /** The (only) account involved does not exist. */
        ACCOUNT_NOT_FOUND,
        /** Transfer: the paying account does not exist. */
        SOURCE_ACCOUNT_NOT_FOUND,
        /** Transfer: the receiving account does not exist. */
        TARGET_ACCOUNT_NOT_FOUND,
        /** Transfer: payer and recipient are the same account. */
        SELF_TRANSFER,
        /** The database failed; the transaction was not applied. Details are in the server log. */
        STORAGE_ERROR
    }

    public TransactionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Returns whether the transaction was committed.
     *
     * @return {@code true} only for {@link Status#SUCCESS}
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * Builds a successful result.
     *
     * @param balance            balance of the affected account after the transaction
     * @param counterpartBalance recipient balance after a transfer, otherwise {@code null}
     * @return the result
     */
    public static TransactionResult success(BigDecimal balance, BigDecimal counterpartBalance) {
        return new TransactionResult(Status.SUCCESS, "OK", balance, counterpartBalance);
    }

    /**
     * Builds a failed result.
     *
     * @param status  the failure reason; not {@link Status#SUCCESS}
     * @param message description of the failure
     * @return the result
     */
    public static TransactionResult failure(Status status, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("a failure cannot have status SUCCESS");
        }
        return new TransactionResult(status, message, null, null);
    }
}

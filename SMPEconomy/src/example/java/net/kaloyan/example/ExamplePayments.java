package net.kaloyan.example;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;

/**
 * Plugin-independent example of using {@link EconomyService}: look up a balance and make a payment.
 * Every method returns a future; the text is produced on the storage thread, so a plugin must send it to
 * players from the main thread (see {@link ExampleConsumerPlugin}).
 */
public final class ExamplePayments {

    private ExamplePayments() {
    }

    /**
     * Describes a player's balance.
     *
     * @param economy the economy service
     * @param player  the player's UUID
     * @return a future of a line of text
     */
    public static CompletableFuture<String> describeBalance(EconomyService economy, UUID player) {
        return economy.getBalance(player).thenApply(balance -> balance
                .map(amount -> "Balance: " + economy.format(amount))
                .orElse("No account yet."));
    }

    /**
     * Pays {@code amount} from one player to another and describes the outcome.
     *
     * @param economy the economy service
     * @param from    the payer's UUID
     * @param to      the recipient's UUID
     * @param amount  the amount, at most two decimal places
     * @return a future of a line of text
     */
    public static CompletableFuture<String> pay(EconomyService economy, UUID from, UUID to, BigDecimal amount) {
        return economy.transfer(from, to, amount).thenApply(result -> describe(economy, amount, result));
    }

    private static String describe(EconomyService economy, BigDecimal amount, TransactionResult result) {
        return switch (result.status()) {
            case SUCCESS -> "Paid " + economy.format(amount) + ". You now have " + economy.format(result.balance()) + ".";
            case INSUFFICIENT_FUNDS -> "You cannot afford that.";
            case SELF_TRANSFER -> "You cannot pay yourself.";
            case SOURCE_ACCOUNT_NOT_FOUND -> "You have no account.";
            case TARGET_ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND -> "The recipient has no account.";
            case INVALID_AMOUNT, AMOUNT_EXCEEDS_LIMIT -> "That amount is not allowed.";
            case BALANCE_LIMIT_EXCEEDED -> "The recipient cannot hold that much money.";
            case STORAGE_ERROR -> "The economy is having trouble; nothing was paid.";
        };
    }
}

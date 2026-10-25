package net.kaloyan.smpvault;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * Vault {@link Economy} implementation backed by SMP-Economy's {@link EconomyService}, so every Vault-based plugin
 * uses the one SMP-Economy balance. No balance is stored here.
 *
 * <p><strong>Threading trade-off.</strong> Vault's API is synchronous, while SMP-Economy is asynchronous. Each call
 * therefore waits for SMP-Economy's answer (a local SQLite write, normally well under a millisecond) for at most
 * {@code mainWaitMs} on the server main thread and {@code otherWaitMs} elsewhere. If no answer arrives in time the
 * call returns {@code FAILURE} with a message starting with {@value #UNCONFIRMED}: the money may or may not have
 * moved, which is logged at SEVERE with player and amount. A definite failure from SMP-Economy changes nothing.
 *
 * <p>Banks are not supported. Accounts are created by SMP-Economy when a player joins; {@code createPlayerAccount}
 * only reports whether one exists.
 */
@SuppressWarnings("deprecation") // Vault's name-based methods are deprecated but the interface still requires them
public final class SmpVaultEconomy implements Economy {

    /** Prefix of the error message when the outcome of a payment could not be confirmed. */
    public static final String UNCONFIRMED = "UNCONFIRMED";

    private static final String NO_BANKS = "SMP-Economy has no banks";

    private final EconomyService economy;
    private final Logger logger;
    private final BooleanSupplier mainThread;
    private final Function<String, Optional<UUID>> nameLookup;
    private final long mainWaitMs;
    private final long otherWaitMs;

    /**
     * @param economy    SMP-Economy's service
     * @param logger     console logger
     * @param mainThread tells whether the caller is the server main thread
     * @param nameLookup resolves a legacy player name (as used by old Vault calls) to a UUID, if known
     * @param mainWaitMs how long a call on the main thread waits for SMP-Economy
     * @param otherWaitMs how long a call on another thread waits
     */
    public SmpVaultEconomy(EconomyService economy, Logger logger, BooleanSupplier mainThread,
                           Function<String, Optional<UUID>> nameLookup, long mainWaitMs, long otherWaitMs) {
        this.economy = economy;
        this.logger = logger;
        this.mainThread = mainThread;
        this.nameLookup = nameLookup;
        this.mainWaitMs = mainWaitMs;
        this.otherWaitMs = otherWaitMs;
    }

    // ---------------------------------------------------------------- plumbing

    private static final class Unconfirmed extends Exception {
        private static final long serialVersionUID = 1L;

        Unconfirmed(Throwable cause) {
            super(cause);
        }
    }

    private static final class NotRun extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private <T> T await(CompletableFuture<T> future) throws Unconfirmed, NotRun {
        long wait = mainThread.getAsBoolean() ? mainWaitMs : otherWaitMs;
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Unconfirmed(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unconfirmed(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException && cause.getCause() instanceof RejectedExecutionException) {
                throw new NotRun(); // SMP-Economy is shut down; nothing was executed
            }
            throw new Unconfirmed(cause);
        }
    }

    private static BigDecimal money(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean badAmount(double amount) {
        return Double.isNaN(amount) || Double.isInfinite(amount);
    }

    private Optional<UUID> resolve(String name) {
        return name == null ? Optional.empty() : nameLookup.apply(name);
    }

    private static EconomyResponse fail(double amount, String message) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, message);
    }

    private EconomyResponse change(UUID id, double amount, boolean deposit) {
        if (badAmount(amount) || amount < 0) {
            return fail(amount, deposit ? "Cannot deposit negative funds" : "Cannot withdraw negative funds");
        }
        BigDecimal value = money(amount);
        if (value.signum() == 0) {
            return new EconomyResponse(0, balanceOf(id), ResponseType.SUCCESS, null);
        }
        try {
            CompletableFuture<TransactionResult> call = deposit ? economy.deposit(id, value) : economy.withdraw(id, value);
            TransactionResult result = await(call);
            if (result != null && result.isSuccess()) {
                return new EconomyResponse(value.doubleValue(), result.balance().doubleValue(), ResponseType.SUCCESS, null);
            }
            if (result == null) {
                throw new Unconfirmed(null);
            }
            String why = switch (result.status()) {
                case INSUFFICIENT_FUNDS -> "Insufficient funds";
                case ACCOUNT_NOT_FOUND -> "No account";
                case BALANCE_LIMIT_EXCEEDED -> "Balance limit exceeded";
                case AMOUNT_EXCEEDS_LIMIT -> "Amount exceeds the transaction limit";
                default -> result.message();
            };
            return fail(amount, why);
        } catch (NotRun e) {
            return fail(amount, "SMP-Economy is not available");
        } catch (Unconfirmed e) {
            logger.log(Level.SEVERE, "PAYMENT OUTCOME UNKNOWN: " + (deposit ? "deposit of " : "withdraw of ") + value
                    + " for " + id + " was not confirmed by SMP-Economy; check the player's balance.", e.getCause());
            return fail(amount, UNCONFIRMED + ": the payment may or may not have been applied");
        }
    }

    private double balanceOf(UUID id) {
        try {
            return await(economy.getBalance(id)).map(BigDecimal::doubleValue).orElse(0.0);
        } catch (Unconfirmed | NotRun e) {
            logger.log(Level.WARNING, "Could not read the balance of " + id, e.getCause());
            return 0.0;
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not read the balance of " + id, e);
            return 0.0;
        }
    }

    private boolean accountExists(UUID id) {
        try {
            return Boolean.TRUE.equals(await(economy.hasAccount(id)));
        } catch (Unconfirmed | NotRun e) {
            return false;
        }
    }

    private boolean funds(UUID id, double amount) {
        if (badAmount(amount) || amount < 0) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(await(economy.hasFunds(id, money(amount))));
        } catch (Unconfirmed | NotRun e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- general

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "SMP-Economy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return economy.getFractionDigits();
    }

    @Override
    public String format(double amount) {
        return economy.format(money(badAmount(amount) ? 0 : amount));
    }

    @Override
    public String currencyNamePlural() {
        return economy.getCurrencySymbol();
    }

    @Override
    public String currencyNameSingular() {
        return economy.getCurrencySymbol();
    }

    // ---------------------------------------------------------------- accounts

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName).map(this::accountExists).orElse(false);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return accountExists(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    // ---------------------------------------------------------------- balances

    @Override
    public double getBalance(String playerName) {
        return resolve(playerName).map(this::balanceOf).orElse(0.0);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balanceOf(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return resolve(playerName).map(id -> funds(id, amount)).orElse(false);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return funds(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ---------------------------------------------------------------- payments

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return resolve(playerName).map(id -> change(id, amount, false)).orElseGet(() -> fail(amount, "Unknown player"));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return change(player.getUniqueId(), amount, false);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return resolve(playerName).map(id -> change(id, amount, true)).orElseGet(() -> fail(amount, "Unknown player"));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return change(player.getUniqueId(), amount, true);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ---------------------------------------------------------------- banks (unsupported)

    private static EconomyResponse noBank() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NO_BANKS);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBank();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}

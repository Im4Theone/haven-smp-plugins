package net.kaloyan.smpmarket.testutil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;

/**
 * In-memory {@link EconomyService} that follows the documented contract of SMP-Economy (atomic transfer, definite
 * failures change nothing) and can be told to misbehave in the ways the real service can: refuse with a status, throw
 * after applying a change (the "uncertain" case), or reject the submission because it is shut down.
 */
public final class FakeEconomy implements EconomyService {

    /** How the next money-changing calls behave. */
    public enum Mode {
        NORMAL,
        /** Return a failure with {@link #failureStatus}; nothing changes. */
        FAIL_STATUS,
        /** Apply the change, then complete exceptionally: the caller cannot know. */
        THROW_AFTER_APPLY,
        /** Do not apply the change and complete exceptionally with a generic error. */
        THROW_BEFORE_APPLY,
        /** Complete exceptionally the way a shut-down SMP-Economy does (provably not executed). */
        REJECTED,
        /** Complete with a {@code null} result. */
        NULL_RESULT
    }

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    public final AtomicInteger transfers = new AtomicInteger();
    public final AtomicInteger withdrawals = new AtomicInteger();
    public final AtomicInteger deposits = new AtomicInteger();
    public final List<String> calls = new CopyOnWriteArrayList<>();

    public volatile Mode mode = Mode.NORMAL;
    /** Behaviour of {@code withdraw} (used for the market fee); independent of {@link #mode}. */
    public volatile Mode withdrawMode = Mode.NORMAL;
    public volatile Status failureStatus = Status.STORAGE_ERROR;
    public volatile BigDecimal maxTransaction = new BigDecimal("1000000000.00");
    public volatile long maxBalanceMinor = 100_000_000_000_000_000L;

    /** Creates an account holding {@code balance}. */
    public UUID account(String balance) {
        UUID id = UUID.randomUUID();
        balances.put(id, minor(new BigDecimal(balance)));
        return id;
    }

    /** Creates (or resets) the account of {@code id}. */
    public void account(UUID id, String balance) {
        balances.put(id, minor(new BigDecimal(balance)));
    }

    /** Current balance in minor units. */
    public long balance(UUID id) {
        return balances.getOrDefault(id, -1L);
    }

    private static long minor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    @Override
    public int getFractionDigits() {
        return 2;
    }

    @Override
    public String getCurrencySymbol() {
        return "$";
    }

    @Override
    public String format(BigDecimal amount) {
        return "$" + amount.setScale(2).toPlainString();
    }

    @Override
    public BigDecimal getMaximumBalance() {
        return BigDecimal.valueOf(maxBalanceMinor, 2);
    }

    @Override
    public BigDecimal getMaximumTransactionAmount() {
        return maxTransaction;
    }

    @Override
    public CompletableFuture<Boolean> hasAccount(UUID player) {
        return CompletableFuture.completedFuture(balances.containsKey(player));
    }

    @Override
    public CompletableFuture<Optional<BigDecimal>> getBalance(UUID player) {
        Long b = balances.get(player);
        return CompletableFuture.completedFuture(b == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(b, 2)));
    }

    @Override
    public CompletableFuture<Boolean> hasFunds(UUID player, BigDecimal amount) {
        Long b = balances.get(player);
        return CompletableFuture.completedFuture(b != null && b >= minor(amount));
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID player, BigDecimal amount) {
        deposits.incrementAndGet();
        calls.add("deposit " + player + " " + amount);
        return run(mode, () -> change(player, minor(amount), true));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID player, BigDecimal amount) {
        withdrawals.incrementAndGet();
        calls.add("withdraw " + player + " " + amount);
        return run(withdrawMode, () -> change(player, minor(amount), false));
    }

    @Override
    public synchronized CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount) {
        transfers.incrementAndGet();
        calls.add("transfer " + from + " " + to + " " + amount);
        return run(mode, () -> move(from, to, minor(amount)));
    }

    private CompletableFuture<TransactionResult> run(Mode behaviour, java.util.function.Supplier<TransactionResult> action) {
        switch (behaviour) {
            case FAIL_STATUS:
                return CompletableFuture.completedFuture(TransactionResult.failure(failureStatus, "injected"));
            case THROW_AFTER_APPLY:
                action.get();
                return CompletableFuture.failedFuture(new IllegalStateException("connection lost after apply"));
            case THROW_BEFORE_APPLY:
                return CompletableFuture.failedFuture(new IllegalStateException("connection lost"));
            case REJECTED:
                return CompletableFuture.failedFuture(new IllegalStateException("SMP-Economy is shut down",
                        new RejectedExecutionException("rejected")));
            case NULL_RESULT:
                return CompletableFuture.completedFuture(null);
            case NORMAL:
            default:
                return CompletableFuture.completedFuture(action.get());
        }
    }

    private synchronized TransactionResult move(UUID from, UUID to, long minor) {
        if (from.equals(to)) {
            return TransactionResult.failure(Status.SELF_TRANSFER, "self");
        }
        Long payer = balances.get(from);
        if (payer == null) {
            return TransactionResult.failure(Status.SOURCE_ACCOUNT_NOT_FOUND, "no payer");
        }
        Long recipient = balances.get(to);
        if (recipient == null) {
            return TransactionResult.failure(Status.TARGET_ACCOUNT_NOT_FOUND, "no recipient");
        }
        if (payer < minor) {
            return TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "insufficient");
        }
        if (recipient + minor > maxBalanceMinor) {
            return TransactionResult.failure(Status.BALANCE_LIMIT_EXCEEDED, "limit");
        }
        balances.put(from, payer - minor);
        balances.put(to, recipient + minor);
        return TransactionResult.success(BigDecimal.valueOf(payer - minor, 2), BigDecimal.valueOf(recipient + minor, 2));
    }

    private synchronized TransactionResult change(UUID player, long minor, boolean credit) {
        Long before = balances.get(player);
        if (before == null) {
            return TransactionResult.failure(Status.ACCOUNT_NOT_FOUND, "no account");
        }
        if (!credit && before < minor) {
            return TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "insufficient");
        }
        long after = credit ? before + minor : before - minor;
        balances.put(player, after);
        return TransactionResult.success(BigDecimal.valueOf(after, 2), null);
    }
}

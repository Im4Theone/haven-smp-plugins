package net.kaloyan.smpvault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests: the Vault facade over a mocked SMP-Economy service. */
class SmpVaultEconomyTest {

    private final UUID id = UUID.randomUUID();
    private final EconomyService service = mock(EconomyService.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private SmpVaultEconomy vault;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(id);
        when(service.getFractionDigits()).thenReturn(2);
        when(service.getCurrencySymbol()).thenReturn("$");
        when(service.format(any())).thenAnswer(inv -> "$" + inv.<BigDecimal>getArgument(0).toPlainString());
        vault = new SmpVaultEconomy(service, Logger.getAnonymousLogger(), () -> true,
                name -> name.equals("Alex") ? Optional.of(id) : Optional.empty(), 150, 150);
    }

    private static TransactionResult ok(String balance) {
        return TransactionResult.success(new BigDecimal(balance), null);
    }

    @Test
    void successfulWithdrawAndDepositReportTheNewBalance() {
        when(service.withdraw(id, new BigDecimal("12.50"))).thenReturn(CompletableFuture.completedFuture(ok("87.50")));
        when(service.deposit(id, new BigDecimal("5.00"))).thenReturn(CompletableFuture.completedFuture(ok("92.50")));

        EconomyResponse w = vault.withdrawPlayer(player, 12.5);
        EconomyResponse d = vault.depositPlayer(player, 5);

        assertTrue(w.transactionSuccess());
        assertEquals(87.5, w.balance);
        assertEquals(12.5, w.amount);
        assertTrue(d.transactionSuccess());
        assertEquals(92.5, d.balance);
    }

    @Test
    void definiteFailuresAreFailuresWithAReason() {
        when(service.withdraw(id, new BigDecimal("500.00"))).thenReturn(
                CompletableFuture.completedFuture(TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "x")));
        EconomyResponse r = vault.withdrawPlayer(player, 500);
        assertFalse(r.transactionSuccess());
        assertEquals(ResponseType.FAILURE, r.type);
        assertEquals("Insufficient funds", r.errorMessage);
    }

    @Test
    void negativeNanAndInfiniteAmountsNeverReachTheEconomy() {
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(vault.withdrawPlayer(player, bad).transactionSuccess());
            assertFalse(vault.depositPlayer(player, bad).transactionSuccess());
        }
        verify(service, never()).withdraw(any(), any());
        verify(service, never()).deposit(any(), any());
    }

    @Test
    void zeroAmountIsASuccessfulNoOp() {
        when(service.getBalance(id)).thenReturn(CompletableFuture.completedFuture(Optional.of(new BigDecimal("10.00"))));
        EconomyResponse r = vault.depositPlayer(player, 0);
        assertTrue(r.transactionSuccess());
        verify(service, never()).deposit(any(), any());
    }

    @Test
    void amountsAreRoundedToCentsNeverTruncated() {
        when(service.deposit(id, new BigDecimal("0.11"))).thenReturn(CompletableFuture.completedFuture(ok("0.11")));
        assertTrue(vault.depositPlayer(player, 0.105).transactionSuccess());
    }

    @Test
    void aPaymentThatNeverAnswersIsReportedAsUnconfirmedNotAsSuccess() {
        when(service.withdraw(id, new BigDecimal("5.00"))).thenReturn(new CompletableFuture<>());
        EconomyResponse r = vault.withdrawPlayer(player, 5);
        assertFalse(r.transactionSuccess());
        assertTrue(r.errorMessage.startsWith(SmpVaultEconomy.UNCONFIRMED));
    }

    @Test
    void anExceptionalOutcomeIsUnconfirmedButAShutDownEconomyIsNot() {
        when(service.withdraw(id, new BigDecimal("5.00"))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("db exploded")));
        assertTrue(vault.withdrawPlayer(player, 5).errorMessage.startsWith(SmpVaultEconomy.UNCONFIRMED));

        when(service.withdraw(id, new BigDecimal("6.00"))).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("SMP-Economy is shut down", new RejectedExecutionException())));
        EconomyResponse down = vault.withdrawPlayer(player, 6);
        assertFalse(down.transactionSuccess());
        assertFalse(down.errorMessage.startsWith(SmpVaultEconomy.UNCONFIRMED));
    }

    @Test
    void balancesAndFundsAndAccounts() {
        when(service.getBalance(id)).thenReturn(CompletableFuture.completedFuture(Optional.of(new BigDecimal("42.25"))));
        when(service.hasFunds(id, new BigDecimal("40.00"))).thenReturn(CompletableFuture.completedFuture(true));
        when(service.hasFunds(id, new BigDecimal("50.00"))).thenReturn(CompletableFuture.completedFuture(false));
        when(service.hasAccount(id)).thenReturn(CompletableFuture.completedFuture(true));

        assertEquals(42.25, vault.getBalance(player));
        assertTrue(vault.has(player, 40));
        assertFalse(vault.has(player, 50));
        assertFalse(vault.has(player, -1));
        assertTrue(vault.hasAccount(player));
        assertTrue(vault.createPlayerAccount(player), "reports the existing account; accounts are made on join");
    }

    @Test
    void legacyNameBasedCallsResolveThroughTheNameLookup() {
        when(service.getBalance(id)).thenReturn(CompletableFuture.completedFuture(Optional.of(new BigDecimal("1.00"))));
        when(service.deposit(id, new BigDecimal("2.00"))).thenReturn(CompletableFuture.completedFuture(ok("3.00")));
        assertEquals(1.0, vault.getBalance("Alex"));
        assertTrue(vault.depositPlayer("Alex", 2).transactionSuccess());
        assertEquals(0.0, vault.getBalance("Nobody"));
        assertFalse(vault.withdrawPlayer("Nobody", 1).transactionSuccess());
    }

    @Test
    void metadataAndBanks() {
        assertEquals("SMP-Economy", vault.getName());
        assertEquals(2, vault.fractionalDigits());
        assertEquals("$12.50", vault.format(12.5));
        assertFalse(vault.hasBankSupport());
        assertEquals(ResponseType.NOT_IMPLEMENTED, vault.bankBalance("x").type);
        assertTrue(vault.getBanks().isEmpty());
    }
}

package net.kaloyan.smpeconomy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import org.junit.jupiter.api.Test;

/** Guards the shape of the public API so a convenient but dangerous method cannot be added by accident. */
class EconomyApiContractTest {

    @Test
    void theApiOffersNoWayToOverwriteOrCreateBalancesDirectly() {
        Set<String> names = Arrays.stream(EconomyService.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());

        assertEquals(Set.of("getFractionDigits", "getCurrencySymbol", "format", "getMaximumBalance",
                "getMaximumTransactionAmount", "hasAccount", "getBalance", "hasFunds", "deposit", "withdraw",
                "transfer"), names, "a new API method needs a deliberate decision and a README entry");
        assertTrue(names.stream().noneMatch(n -> n.startsWith("set") || n.startsWith("create") || n.contains("Account") && !n.equals("hasAccount")));
    }

    @Test
    void everyMethodThatTouchesStoredDataIsAsynchronous() {
        Set<String> synchronousByDesign = Set.of("getFractionDigits", "getCurrencySymbol", "format",
                "getMaximumBalance", "getMaximumTransactionAmount");

        for (Method method : EconomyService.class.getDeclaredMethods()) {
            if (!synchronousByDesign.contains(method.getName())) {
                assertEquals(CompletableFuture.class, method.getReturnType(), method.getName());
            }
        }
    }

    @Test
    void resultsAreImmutableAndConsistent() {
        TransactionResult ok = TransactionResult.success(new BigDecimal("1.00"), null);
        TransactionResult bad = TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "no");

        assertTrue(ok.isSuccess());
        assertFalse(bad.isSuccess());
        assertEquals(null, bad.balance());
        assertThrows(IllegalArgumentException.class, () -> TransactionResult.failure(Status.SUCCESS, "x"));
        assertThrows(NullPointerException.class, () -> new TransactionResult(null, "x", null, null));
    }
}

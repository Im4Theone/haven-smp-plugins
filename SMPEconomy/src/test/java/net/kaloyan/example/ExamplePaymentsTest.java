package net.kaloyan.example;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import net.kaloyan.smpeconomy.storage.EconomyStore;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the documented example against a real service and database, so the README snippet is proven to work. */
class ExamplePaymentsTest {

    @TempDir
    Path dir;

    @Test
    void exampleLooksUpBalancesAndPays() throws Exception {
        LogCapture log = new LogCapture();
        EconomySettings settings = TestSupport.smallLimits();
        try (EconomyStore store = TestSupport.openStore(dir, () -> settings, log)) {
            EconomyServiceImpl economy = new EconomyServiceImpl(store, Runnable::run, () -> settings,
                    new DebugLogger(log.logger(), () -> settings));
            UUID alex = TestSupport.account(store, "Alex", "50.00");
            UUID sam = TestSupport.account(store, "Sam", "0.00");

            assertEquals("Balance: $50.00", ExamplePayments.describeBalance(economy, alex).get());
            assertEquals("No account yet.", ExamplePayments.describeBalance(economy, UUID.randomUUID()).get());
            assertEquals("Paid $20.00. You now have $30.00.", ExamplePayments.pay(economy, alex, sam, money("20")).get());
            assertEquals("You cannot afford that.", ExamplePayments.pay(economy, alex, sam, money("31")).get());
            assertEquals("You cannot pay yourself.", ExamplePayments.pay(economy, alex, alex, money("1")).get());
            assertEquals("The recipient has no account.", ExamplePayments.pay(economy, alex, UUID.randomUUID(), money("1")).get());
            assertEquals("That amount is not allowed.", ExamplePayments.pay(economy, alex, sam, money("-1")).get());
        }
    }
}

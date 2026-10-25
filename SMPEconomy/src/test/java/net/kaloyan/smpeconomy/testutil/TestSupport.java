package net.kaloyan.smpeconomy.testutil;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.storage.EconomyStore;

/** Small factories shared by tests. Every test uses its own temporary directory. */
public final class TestSupport {

    private TestSupport() {
    }

    /** Parses a decimal literal. */
    public static BigDecimal money(String text) {
        return new BigDecimal(text);
    }

    /** Settings with a 100.00 starting balance, 1,000.00 maximum balance and 500.00 maximum transaction. */
    public static EconomySettings smallLimits() {
        return new EconomySettings("$", 10_000L, 100_000L, 50_000L, false);
    }

    /** A mutable settings holder, so tests can emulate a reload. */
    public static AtomicReference<EconomySettings> settingsHolder(EconomySettings initial) {
        return new AtomicReference<>(initial);
    }

    /** Opens a store on {@code <dir>/economy.db}. */
    public static EconomyStore openStore(Path dir, Supplier<EconomySettings> settings, LogCapture log) {
        return EconomyStore.open(dir.resolve("economy.db"), settings, log.logger());
    }

    /** Creates an account with {@code balance} by initializing it and then adjusting it. */
    public static UUID account(EconomyStore store, String name, String balance) {
        UUID id = UUID.randomUUID();
        store.initializeAccount(id, name);
        if (!store.setBalance(id, money(balance)).isSuccess()) {
            throw new IllegalStateException("could not set up test account");
        }
        return id;
    }

    /** Writes {@code yaml} to {@code <dir>/config.yml}. */
    public static Path writeConfig(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        return file;
    }
}

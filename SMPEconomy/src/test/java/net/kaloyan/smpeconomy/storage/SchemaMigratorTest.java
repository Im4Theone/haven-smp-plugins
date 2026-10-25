package net.kaloyan.smpeconomy.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigratorTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();

    private Connection raw() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("economy.db"));
    }

    private int userVersion() throws SQLException {
        try (Connection c = raw()) {
            return SchemaMigrator.readVersion(c);
        }
    }

    private boolean schemaObjectExists(String type, String name) throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type = '" + type + "' AND name = '" + name + "'")) {
            return rs.next();
        }
    }

    @Test
    void aFreshDatabaseIsCreatedAtTheLatestVersion() throws Exception {
        TestSupport.openStore(dir, TestSupport::smallLimits, log).close();

        assertEquals(SchemaMigrator.LATEST_VERSION, userVersion());
        assertTrue(schemaObjectExists("table", "accounts"));
        assertTrue(schemaObjectExists("index", "accounts_last_name"));
    }

    @Test
    void reopeningAnUpToDateDatabaseChangesNothingAndKeepsItsData() throws Exception {
        UUID id;
        try (EconomyStore first = TestSupport.openStore(dir, TestSupport::smallLimits, log)) {
            id = TestSupport.account(first, "Alex", "42.00");
        }

        try (EconomyStore second = TestSupport.openStore(dir, TestSupport::smallLimits, log)) {
            assertEquals(0, TestSupport.money("42.00").compareTo(second.balance(id).orElseThrow()));
        }
        assertEquals(SchemaMigrator.LATEST_VERSION, userVersion());
    }

    @Test
    void aDatabaseFromANewerVersionIsRefusedAndLeftUntouched() throws Exception {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE future_things (x INTEGER)");
            s.execute("INSERT INTO future_things VALUES (7)");
            s.execute("PRAGMA user_version = " + (SchemaMigrator.LATEST_VERSION + 1));
        }

        StorageException e = assertThrows(StorageException.class,
                () -> TestSupport.openStore(dir, TestSupport::smallLimits, log));

        assertTrue(e.getMessage().contains("newer"), e.getMessage());
        assertEquals(SchemaMigrator.LATEST_VERSION + 1, userVersion());
        assertTrue(schemaObjectExists("table", "future_things"));
        assertFalse(schemaObjectExists("table", "accounts"), "a refused database must not get our tables");
    }

    @Test
    void aFailingMigrationStepRollsBackCompletelyAndKeepsTheOldVersion() throws Exception {
        // An unrelated table that already uses the name "accounts" makes step 1 fail halfway.
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE accounts (something_else TEXT)");
        }

        assertThrows(StorageException.class, () -> TestSupport.openStore(dir, TestSupport::smallLimits, log));

        assertEquals(0, userVersion(), "the version must not advance when a step fails");
        assertFalse(schemaObjectExists("index", "accounts_last_name"));
    }

    @Test
    void theUniqueNameIndexRejectsDuplicateNamesAtTheDatabaseLevel() throws Exception {
        TestSupport.openStore(dir, TestSupport::smallLimits, log).close();

        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accounts VALUES ('" + UUID.randomUUID() + "', 1, 'Dup', 1, 1)");
            assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO accounts VALUES ('" + UUID.randomUUID() + "', 1, 'dUP', 1, 1)"));
            // Accounts without a name are unlimited.
            s.execute("INSERT INTO accounts VALUES ('" + UUID.randomUUID() + "', 1, NULL, 1, 1)");
            s.execute("INSERT INTO accounts VALUES ('" + UUID.randomUUID() + "', 1, NULL, 1, 1)");
        }
    }
}

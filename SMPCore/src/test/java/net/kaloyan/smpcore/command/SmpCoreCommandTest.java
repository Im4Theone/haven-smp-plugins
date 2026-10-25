package net.kaloyan.smpcore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.log.DebugLogger;
import net.kaloyan.smpcore.testutil.LogCapture;
import net.kaloyan.smpcore.testutil.TestSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SmpCoreCommandTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<Runnable> ioTasks = new ArrayList<>();
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private ConfigManager config;

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.writeConfig(dir, "logging:\n  debug: false\n");
        // I/O tasks are queued so tests can prove a denied reload never even schedules one.
        config = new ConfigManager(dir.resolve("config.yml"), ioTasks::add, log.logger());
        config.loadInitial();

        Messages messages = new Messages(config::current);
        DebugLogger debug = new DebugLogger(log.logger(), config::current);
        SmpCoreCommand command = new SmpCoreCommand("1.0.0", enabled::get, config, messages, Runnable::run,
                log.logger(), debug);
        dispatcher.getRoot().addChild(command.build());
    }

    private static CommandSender sender(String... permissions) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Tester");
        for (String permission : permissions) {
            when(sender.hasPermission(permission)).thenReturn(true);
        }
        return sender;
    }

    private int run(CommandSender sender, String input) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return dispatcher.execute(input, source);
    }

    private static String sent(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .reduce("", (a, b) -> a + b + "\n");
    }

    private void runQueuedIo() {
        List<Runnable> tasks = new ArrayList<>(ioTasks);
        ioTasks.clear();
        tasks.forEach(Runnable::run);
    }

    // ---- /smpcore info ----

    @Test
    void infoShowsVersionAndStatusToPermittedSender() throws Exception {
        CommandSender sender = sender("smpcore.info");

        int result = run(sender, "smpcore info");

        String output = sent(sender);
        assertEquals(1, result);
        assertTrue(output.contains("1.0.0"), output);
        assertTrue(output.contains("Status: enabled"), output);
        assertTrue(output.startsWith("[SMP] "), "prefix expected: " + output);
    }

    @Test
    void infoReportsDisabledStatus() throws Exception {
        enabled.set(false);
        CommandSender sender = sender("smpcore.info");

        run(sender, "smpcore info");

        assertTrue(sent(sender).contains("Status: disabled"));
    }

    @Test
    void infoIsDeniedWithoutPermissionAndRevealsNothing() throws Exception {
        CommandSender sender = sender();

        // hidden from players without any SMP-Core permission: the command does not even parse for them
        org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> run(sender, "smpcore info"));
        String output = sent(sender);
        assertFalse(output.contains("1.0.0"), output);
        assertFalse(output.contains("Status"), output);
    }

    @Test
    void adminOnlySenderStillNeedsInfoPermissionInTheCommandItself() throws Exception {
        // plugin.yml makes smpcore.admin imply smpcore.info; the command checks the node it documents.
        CommandSender sender = sender("smpcore.admin");

        assertEquals(0, run(sender, "smpcore info"));
    }

    // ---- /smpcore reload ----

    @Test
    void reloadAppliesNewSettingsForAdmin() throws Exception {
        CommandSender sender = sender("smpcore.admin");
        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");

        int result = run(sender, "smpcore reload");
        runQueuedIo();

        assertEquals(1, result);
        assertTrue(config.current().debug());
        assertTrue(sent(sender).contains("Configuration reloaded."), sent(sender));
        assertTrue(log.messages(Level.INFO).stream().anyMatch(m -> m.contains("reloaded by Tester")));
    }

    @Test
    void reloadIsDeniedWithoutAdminAndDoesNotTouchTheFile() throws Exception {
        CommandSender sender = sender("smpcore.info"); // info alone must not grant reload
        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");

        int result = run(sender, "smpcore reload");
        runQueuedIo();

        String output = sent(sender);
        assertEquals(0, result);
        assertTrue(output.contains("smpcore.admin"), output);
        assertTrue(ioTasks.isEmpty());
        assertFalse(config.current().debug(), "settings must be untouched");
        assertFalse(output.contains("reloaded"), output);
    }

    @Test
    void reloadWithInvalidConfigReportsProblemsAndKeepsOldSettings() throws Exception {
        CommandSender sender = sender("smpcore.admin");
        TestSupport.writeConfig(dir, "prefix: \"\"\nlogging:\n  debug: true\n");

        run(sender, "smpcore reload");
        runQueuedIo();

        String output = sent(sender);
        assertTrue(output.contains("Reload rejected"), output);
        assertTrue(output.contains("prefix"), "the specific problem should be shown: " + output);
        assertFalse(config.current().debug(), "previous settings must stay active");
    }

    @Test
    void reloadWithUnparseableYamlReportsFailure() throws Exception {
        CommandSender sender = sender("smpcore.admin");
        TestSupport.writeConfig(dir, "logging: [unclosed\n");

        run(sender, "smpcore reload");
        runQueuedIo();

        assertTrue(sent(sender).contains("not valid YAML"), sent(sender));
    }

    @Test
    void reloadFeedbackWaitsForTheAsyncReadToFinish() throws Exception {
        CommandSender sender = sender("smpcore.admin");

        run(sender, "smpcore reload");

        assertEquals(1, ioTasks.size(), "file read must be scheduled off-thread");
        assertFalse(sent(sender).contains("Configuration reloaded."), "no success message before the read completes");
    }

    // ---- usage and argument errors ----

    @Test
    void bareCommandListsOnlyWhatTheSenderMayUse() throws Exception {
        CommandSender infoOnly = sender("smpcore.info");
        CommandSender both = sender("smpcore.info", "smpcore.admin");

        run(infoOnly, "smpcore");
        run(both, "smpcore");

        assertTrue(sent(infoOnly).contains("/smpcore info"));
        assertFalse(sent(infoOnly).contains("/smpcore reload"));
        assertTrue(sent(both).contains("/smpcore reload"));
    }

    @Test
    void bareCommandTellsUnprivilegedSenderTheyHaveNoAccess() throws Exception {
        CommandSender sender = sender();

        org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> run(sender, "smpcore"));
        assertEquals("", sent(sender));
    }

    @Test
    void unknownSubcommandGetsHelpfulMessage() throws Exception {
        CommandSender sender = sender("smpcore.info", "smpcore.admin");

        int result = run(sender, "smpcore frobnicate now");

        String output = sent(sender);
        assertEquals(0, result);
        assertTrue(output.contains("Unknown subcommand 'frobnicate now'"), output);
        assertTrue(output.contains("/smpcore info"), output);
    }

    @Test
    void extraArgumentsAreRejectedWithUsage() throws Exception {
        CommandSender sender = sender("smpcore.info", "smpcore.admin");

        assertEquals(0, run(sender, "smpcore info please"));
        assertEquals(0, run(sender, "smpcore reload now"));

        String output = sent(sender);
        assertTrue(output.contains("Too many arguments. Usage: /smpcore info"), output);
        assertTrue(output.contains("Too many arguments. Usage: /smpcore reload"), output);
        assertTrue(ioTasks.isEmpty(), "a malformed reload must not reload anything");
    }

    @Test
    void extraArgumentsDoNotBypassPermissionChecks() throws Exception {
        CommandSender sender = sender("smpcore.info");     // may use the command, but not reload

        run(sender, "smpcore reload now");

        String output = sent(sender);
        assertTrue(output.contains("smpcore.admin"), output);
        assertFalse(output.contains("Too many arguments"), output);
    }
}

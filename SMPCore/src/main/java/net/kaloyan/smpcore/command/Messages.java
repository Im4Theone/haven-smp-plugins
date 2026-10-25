package net.kaloyan.smpcore.command;

import java.util.function.Supplier;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/**
 * Builds and sends prefixed chat messages. The prefix is read from the live settings on every call,
 * so a reload changes it immediately.
 */
public final class Messages {

    private final Supplier<CoreSettings> settings;

    public Messages(Supplier<CoreSettings> settings) {
        this.settings = settings;
    }

    /**
     * Sends a neutral (gray) message.
     *
     * @param recipient who receives the message
     * @param text      plain text, not parsed for formatting
     */
    public void info(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.GRAY));
    }

    /**
     * Sends a success (green) message.
     *
     * @param recipient who receives the message
     * @param text      plain text, not parsed for formatting
     */
    public void success(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.GREEN));
    }

    /**
     * Sends an error (red) message.
     *
     * @param recipient who receives the message
     * @param text      plain text, not parsed for formatting
     */
    public void error(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.RED));
    }

    /**
     * Sends an already-built component after the prefix.
     *
     * @param recipient who receives the message
     * @param body      the message body
     */
    public void send(CommandSender recipient, Component body) {
        // The prefix is a sibling, not the parent, so any style left open in it cannot leak into the body.
        Component prefix = MiniMessage.miniMessage().deserialize(settings.get().prefix());
        recipient.sendMessage(Component.text().append(prefix).append(Component.space()).append(body).build());
    }
}

package net.kaloyan.smpeconomy.command;

import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/**
 * Builds and sends prefixed chat messages. The prefix (a MiniMessage string, shared with SMP-Core) is read
 * on every call, so a reload of SMP-Core's prefix applies immediately. Message bodies are plain text and
 * are never parsed for formatting, so player names or currency symbols cannot inject formatting.
 */
public final class Messages {

    private final Supplier<String> prefix;

    /**
     * @param prefix supplies the current MiniMessage prefix
     */
    public Messages(Supplier<String> prefix) {
        this.prefix = prefix;
    }

    /**
     * Sends a neutral (gray) message.
     *
     * @param recipient who receives the message
     * @param text      plain text
     */
    public void info(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.GRAY));
    }

    /**
     * Sends a success (green) message.
     *
     * @param recipient who receives the message
     * @param text      plain text
     */
    public void success(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.GREEN));
    }

    /**
     * Sends an error (red) message.
     *
     * @param recipient who receives the message
     * @param text      plain text
     */
    public void error(CommandSender recipient, String text) {
        send(recipient, Component.text(text, NamedTextColor.RED));
    }

    private void send(CommandSender recipient, Component body) {
        // The prefix is a sibling, not the parent, so any style left open in it cannot leak into the body.
        Component prefixComponent = MiniMessage.miniMessage().deserialize(prefix.get());
        recipient.sendMessage(Component.text().append(prefixComponent).append(Component.space()).append(body).build());
    }
}

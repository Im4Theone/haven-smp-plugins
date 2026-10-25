package net.kaloyan.smpcrates;

import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Texts from the config, rendered with MiniMessage. Strings are inserted as plain text, Components as they are. */
public final class Messages {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Supplier<CratesSettings> settings;

    /** @param settings the current settings */
    public Messages(Supplier<CratesSettings> settings) {
        this.settings = settings;
    }

    /**
     * @param key          the message key
     * @param placeholders name, value pairs; a value is a String or a Component
     * @return the rendered message
     */
    public Component render(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            values.resolver(value instanceof Component c ? Placeholder.component(name, c) : Placeholder.unparsed(name, String.valueOf(value)));
        }
        return mini.deserialize(settings.get().message(key), values.build());
    }

    /** @param miniText MiniMessage text, e.g. a crate's display name */
    public Component parse(String miniText) {
        return mini.deserialize(miniText);
    }

    /**
     * Sends a message.
     *
     * @param to           who gets it
     * @param key          the message key
     * @param placeholders name, value pairs
     */
    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(render(key, placeholders));
    }
}
